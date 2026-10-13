package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.LiveActivityToken;
import io.sitprep.sitprepapi.domain.LiveLocationSession;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.LiveActivityTokenRepo;
import io.sitprep.sitprepapi.repo.LiveLocationSessionRepo;
import io.sitprep.sitprepapi.service.ActivationExpirySweepService;
import io.sitprep.sitprepapi.service.LiveActivityPushService;
import io.sitprep.sitprepapi.service.LiveActivityTokenService.RegisterRequest;
import io.sitprep.sitprepapi.service.LiveActivityTokenService.TokenDto;
import io.sitprep.sitprepapi.service.NotificationService;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * live_activities P1: {@code PUT /api/live-activities/tokens} and
 * {@code DELETE /api/live-activities/tokens/{activityId}}, over the real
 * services and the H2 schema. A token row is the right to receive a group's
 * counts on a lock screen, so the owner is the verified caller and a situation
 * token needs a seat on that group's roster.
 */
@SpringBootTest
@ActiveProfiles("test")
class LiveActivityTokenResourceTest {

    // No background pushes from this context.
    @MockBean LiveActivityPushService pushService;
    @MockBean WebSocketMessageSender ws;
    @MockBean NotificationService notifications;

    @Autowired LiveActivityTokenResource resource;
    @Autowired LiveActivityTokenRepo repo;
    @Autowired GroupRepo groups;
    @Autowired LiveLocationSessionRepo sessions;
    @Autowired ActivationExpirySweepService sweep;

    private String owner;
    private String member;
    private String stranger;
    private Group hh;

    @BeforeEach
    void setUp() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        owner = "owner-" + sfx + "@x.com";
        member = "member-" + sfx + "@x.com";
        stranger = "stranger-" + sfx + "@x.com";
        hh = household(owner, owner, member);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String apns() {
        byte[] b = new byte[32];
        new java.util.Random().nextBytes(b);
        return HexFormat.of().formatHex(b).toUpperCase();
    }

    private RegisterRequest situation(String groupId, String activityId, String apnsToken) {
        return new RegisterRequest("situation", "update", groupId, activityId, apnsToken, "fcm-token-" + activityId);
    }

    private static int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    void noVerifiedCallerIs401_andNothingIsWritten() {
        long before = repo.count();
        assertThat(status(() -> resource.register(situation(hh.getGroupId(), "a1", apns())))).isEqualTo(401);
        assertThat(status(() -> resource.unregister("a1"))).isEqualTo(401);
        assertThat(repo.count()).isEqualTo(before);
    }

    @Test
    void aMemberRegisters_andTheRowIsTheirsWithNoTokenEchoed() {
        as(member);
        String token = apns();
        TokenDto dto = resource.register(situation(hh.getGroupId(), "act-1", token)).getBody();

        assertThat(dto.kind()).isEqualTo("situation");
        assertThat(dto.tokenType()).isEqualTo("update");
        assertThat(dto.referenceId()).isEqualTo(hh.getGroupId());
        assertThat(dto.activityId()).isEqualTo("act-1");
        LiveActivityToken row = repo.findByApnsToken(token.toLowerCase()).orElseThrow();
        assertThat(row.getUserEmail()).isEqualTo(member);
        assertThat(row.getFcmToken()).isEqualTo("fcm-token-act-1");
        assertThat(row.getEndedAt()).isNull();
        assertThat(dto.toString()).doesNotContain(token.toLowerCase()).doesNotContain("fcm-token");
    }

    @Test
    void aStrangerCannotFollowAGroupsCheckIn() {
        as(stranger);
        assertThat(status(() -> resource.register(situation(hh.getGroupId(), "act-x", apns())))).isEqualTo(403);
        assertThat(status(() -> resource.register(situation("no-such-group", "act-y", apns())))).isEqualTo(404);
    }

    @Test
    void aLocationShareTokenIsOnlyForTheSharer() {
        LiveLocationSession s = new LiveLocationSession();
        s.setUserEmail(owner);
        s.setScopeType("group");
        s.setGroupIds(new LinkedHashSet<>(List.of(hh.getGroupId())));
        s.setStartedAt(Instant.now());
        s.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        s.setCreatedByUser(true);
        s.setUploadTokenHash("h");
        LiveLocationSession saved = sessions.save(s);

        as(member);
        assertThat(status(() -> resource.register(new RegisterRequest("location_share", "update",
                saved.getId(), "share-1", apns(), "fcm")))).isEqualTo(403);
        as(owner);
        assertThat(status(() -> resource.register(new RegisterRequest("location_share", "update",
                saved.getId(), "share-1", apns(), "fcm")))).isEqualTo(200);
    }

    @Test
    void badBodiesAre400() {
        as(member);
        assertThat(status(() -> resource.register(new RegisterRequest("weather", "update", hh.getGroupId(), "a", apns(), "f")))).isEqualTo(400);
        assertThat(status(() -> resource.register(new RegisterRequest("situation", "broadcast", hh.getGroupId(), "a", apns(), "f")))).isEqualTo(400);
        assertThat(status(() -> resource.register(situation(hh.getGroupId(), "a", "not-hex!")))).isEqualTo(400);
        assertThat(status(() -> resource.register(new RegisterRequest("situation", "update", hh.getGroupId(), "a", apns(), " ")))).isEqualTo(400);
        assertThat(status(() -> resource.register(new RegisterRequest("situation", "update", null, "a", apns(), "f")))).isEqualTo(400);
        assertThat(status(() -> resource.register(new RegisterRequest("situation", "update", hh.getGroupId(), null, apns(), "f")))).isEqualTo(400);
    }

    @Test
    void reRegisteringTheSameTokenUpdatesOneRow_andARotationRetiresTheOldOne() {
        as(member);
        String first = apns();
        resource.register(situation(hh.getGroupId(), "act-2", first));
        resource.register(new RegisterRequest("situation", "update", hh.getGroupId(), "act-2", first, "fcm-new"));
        assertThat(repo.findByApnsToken(first.toLowerCase()).orElseThrow().getFcmToken()).isEqualTo("fcm-new");

        String rotated = apns();
        resource.register(situation(hh.getGroupId(), "act-2", rotated));

        assertThat(repo.findByApnsToken(first.toLowerCase()).orElseThrow().getEndedAt()).isNotNull();
        assertThat(repo.findByApnsToken(rotated.toLowerCase()).orElseThrow().getEndedAt()).isNull();
        assertThat(repo.findLiveUpdateTokens("situation", hh.getGroupId()))
                .extracting(LiveActivityToken::getActivityId).containsExactly("act-2");
    }

    @Test
    void pushToStartNeedsNoReference() {
        as(member);
        TokenDto dto = resource.register(new RegisterRequest("checkin_request", "push_to_start",
                "ignored", "ignored", apns(), "fcm")).getBody();
        assertThat(dto.referenceId()).isNull();
        assertThat(dto.activityId()).isNull();
    }

    @Test
    void deleteEndsOnlyTheCallersRows_andIsIdempotent() {
        as(member);
        String mine = apns();
        resource.register(situation(hh.getGroupId(), "shared-id", mine));
        as(owner);
        String theirs = apns();
        resource.register(situation(hh.getGroupId(), "shared-id", theirs));

        // The owner's DELETE of the same activity id leaves the member's row alone.
        assertThat(resource.unregister("shared-id").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(repo.findByApnsToken(theirs.toLowerCase()).orElseThrow().getEndedAt()).isNotNull();
        assertThat(repo.findByApnsToken(mine.toLowerCase()).orElseThrow().getEndedAt()).isNull();

        assertThat(resource.unregister("shared-id").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(resource.unregister("never-registered").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void theHourlySweepRetiresDeadTokens_andDeletesOldEndedRows() {
        Instant now = Instant.now();
        LiveActivityToken stale = row(now.minus(Duration.ofHours(13)), null);
        LiveActivityToken fresh = row(now.minus(Duration.ofHours(1)), null);
        LiveActivityToken oldEnded = row(now.minus(Duration.ofDays(9)), now.minus(Duration.ofDays(8)));
        LiveActivityToken recentEnded = row(now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));

        sweep.scheduledSweep();

        assertThat(repo.findById(stale.getId()).orElseThrow().getEndedAt()).isNotNull();
        assertThat(repo.findById(fresh.getId()).orElseThrow().getEndedAt()).isNull();
        assertThat(repo.findById(oldEnded.getId())).isEmpty();
        assertThat(repo.findById(recentEnded.getId())).isPresent();
    }

    private LiveActivityToken row(Instant updatedAt, Instant endedAt) {
        LiveActivityToken t = new LiveActivityToken();
        t.setUserEmail(member);
        t.setKind("situation");
        t.setTokenType("update");
        t.setReferenceId(hh.getGroupId());
        t.setActivityId("sweep-" + UUID.randomUUID().toString().substring(0, 8));
        t.setApnsToken(apns().toLowerCase());
        t.setFcmToken("fcm");
        t.setCreatedAt(updatedAt);
        t.setUpdatedAt(updatedAt);
        t.setEndedAt(endedAt);
        LiveActivityToken saved = repo.save(t);
        // @PreUpdate never fires on insert, but make sure the stamp is the one we set.
        assertThat(saved.getUpdatedAt()).isEqualTo(updatedAt);
        return saved;
    }

    private static void as(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private Group household(String owner, String... members) {
        Group g = new Group();
        g.setGroupId("hh-" + UUID.randomUUID());
        g.setGroupType("Household");
        g.setGroupName("Test household");
        g.setPrivacy("Private");
        g.setAlert("Active");
        g.setOwnerEmail(owner);
        g.setAdminEmails(new ArrayList<>(List.of(owner)));
        g.setMemberEmails(new ArrayList<>(List.of(members)));
        g.setPendingMemberEmails(new ArrayList<>());
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());
        return groups.save(g);
    }
}
