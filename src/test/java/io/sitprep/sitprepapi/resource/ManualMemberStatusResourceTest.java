package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdEvent;
import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdEventRepo;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.HouseholdEventService;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Household drawer gameplan §3.4 / §5.1 — an owner or admin answers for a
 * manual member (V103). Over the real services and the H2 schema, so the new
 * columns are exercised, not mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
class ManualMemberStatusResourceTest {

    @MockBean WebSocketMessageSender ws;
    @MockBean NotificationService notifications;

    @Autowired HouseholdManualMemberResource resource;
    @Autowired GroupRepo groups;
    @Autowired UserInfoRepo users;
    @Autowired HouseholdManualMemberRepo manualRepo;
    @Autowired HouseholdEventRepo eventRepo;

    private String owner;
    private String member;
    private Group hh;
    private HouseholdManualMember chris;

    @BeforeEach
    void setUp() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        owner = "owner-" + sfx + "@x.com";
        member = "member-" + sfx + "@x.com";
        hh = household(owner, owner, member);
        user(owner, "Dione");
        user(member, "Francis");
        chris = manual(hh.getGroupId(), "Chris");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anOwnerSetsSafe_andTheRowSaysWhoSetIt() {
        as(owner);
        HouseholdManualMemberDto dto = resource.setStatus(hh.getGroupId(), chris.getId(),
                new HouseholdManualMemberResource.StatusRequest("safe")).getBody();

        assertThat(dto.status()).isNotNull();
        assertThat(dto.status().value()).isEqualTo("SAFE");
        assertThat(dto.status().color()).isEqualTo("#1BBC9B");
        assertThat(dto.status().setByName()).isEqualTo("Dione");
        assertThat(dto.status().showUntil()).isAfter(Instant.now().plusSeconds(23 * 3600));
        HouseholdManualMember stored = manualRepo.findById(chris.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("SAFE");
        assertThat(stored.getStatusSetByEmail()).isEqualTo(owner);

        // Out on the manual-member socket, and a status-set-for row in this household.
        verify(ws).sendHouseholdManualMemberUpdate(eq(hh.getGroupId()), any());
        List<HouseholdEvent> rows = eventRepo.findRangeByKind(hh.getGroupId(),
                HouseholdEventService.KIND_STATUS_SET_FOR, Instant.EPOCH, Instant.now().plusSeconds(5));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getActorEmail()).isEqualTo(owner);
        assertThat(rows.get(0).getPayloadJson()).contains("\"subjectManualId\":\"" + chris.getId() + "\"")
                .contains("\"subjectName\":\"Chris\"").contains("\"status\":\"SAFE\"");
        // No push for a manual status.
        verifyNoInteractions(notifications);
    }

    @Test
    void injuredNeverLapses() {
        as(owner);
        HouseholdManualMemberDto dto = resource.setStatus(hh.getGroupId(), chris.getId(),
                new HouseholdManualMemberResource.StatusRequest("INJURED")).getBody();
        assertThat(dto.status().value()).isEqualTo("INJURED");
        assertThat(dto.status().showUntil()).isNull();
    }

    @Test
    void aPlainMemberIs403_andNothingIsWritten() {
        as(member);
        assertThatThrownBy(() -> resource.setStatus(hh.getGroupId(), chris.getId(),
                new HouseholdManualMemberResource.StatusRequest("SAFE")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> resource.clearStatus(hh.getGroupId(), chris.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(manualRepo.findById(chris.getId()).orElseThrow().getStatus()).isNull();
    }

    @Test
    void aMemberOfAnotherHouseholdIs404() {
        Group other = household(owner, owner);
        HouseholdManualMember elsewhere = manual(other.getGroupId(), "Sam");
        as(owner);
        assertThatThrownBy(() -> resource.setStatus(hh.getGroupId(), elsewhere.getId(),
                new HouseholdManualMemberResource.StatusRequest("SAFE")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(manualRepo.findById(elsewhere.getId()).orElseThrow().getStatus()).isNull();
    }

    @Test
    void aBadValueIs400() {
        as(owner);
        for (String bad : new String[]{"NO RESPONSE", "fine", ""}) {
            assertThatThrownBy(() -> resource.setStatus(hh.getGroupId(), chris.getId(),
                    new HouseholdManualMemberResource.StatusRequest(bad)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThatThrownBy(() -> resource.setStatus(hh.getGroupId(), chris.getId(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void deleteClearsIt() {
        as(owner);
        resource.setStatus(hh.getGroupId(), chris.getId(),
                new HouseholdManualMemberResource.StatusRequest("HELP"));

        HouseholdManualMemberDto dto = resource.clearStatus(hh.getGroupId(), chris.getId()).getBody();

        assertThat(dto.status()).isNull();
        HouseholdManualMember stored = manualRepo.findById(chris.getId()).orElseThrow();
        assertThat(stored.getStatus()).isNull();
        assertThat(stored.getStatusUpdatedAt()).isNull();
        assertThat(stored.getStatusSetByEmail()).isNull();
        List<HouseholdEvent> rows = eventRepo.findRangeByKind(hh.getGroupId(),
                HouseholdEventService.KIND_STATUS_SET_FOR, Instant.EPOCH, Instant.now().plusSeconds(5));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).getPayloadJson()).contains("\"status\":\"CLEARED\"").contains("\"cleared\":true");
    }

    // ── fixtures ─────────────────────────────────────────────────────────

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
        g.setAlert("Not Active");
        g.setOwnerEmail(owner);
        g.setAdminEmails(new ArrayList<>(List.of(owner)));
        g.setMemberEmails(new ArrayList<>(List.of(members)));
        g.setPendingMemberEmails(new ArrayList<>());
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());
        return groups.save(g);
    }

    private void user(String email, String first) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setUserFirstName(first);
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        users.save(u);
    }

    private HouseholdManualMember manual(String hid, String name) {
        HouseholdManualMember m = new HouseholdManualMember();
        m.setId(UUID.randomUUID().toString());
        m.setHouseholdId(hid);
        m.setName(name);
        m.setIsAdult(false);
        m.setBand(HouseholdBand.KID);
        return manualRepo.save(m);
    }
}
