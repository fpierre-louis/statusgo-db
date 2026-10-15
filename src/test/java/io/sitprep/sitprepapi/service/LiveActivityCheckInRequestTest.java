package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.messaging.Message;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.LiveActivityToken;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.LiveActivityTokenRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "You were asked to check in" (Live Activities P2, 2026-10-14): push-to-start
 * to every OTHER member the policy would interrupt, silent; ended as answered
 * when the member's status lands; ended with the check-in.
 */
class LiveActivityCheckInRequestTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STARTER = "dione@x.com";

    private LiveActivityTokenRepo tokens;
    private GroupRepo groups;
    private PushPolicyService policy;
    private final List<Message> sent = new ArrayList<>();
    private LiveActivityPushService service;

    @BeforeEach
    void setUp() {
        tokens = mock(LiveActivityTokenRepo.class);
        groups = mock(GroupRepo.class);
        policy = mock(PushPolicyService.class);
        service = new LiveActivityPushService(tokens, () -> mock(LiveActivityStateBuilder.class), e -> false,
                message -> { sent.add(message); return "projects/x/messages/1"; },
                (task, delay) -> { }, Runnable::run, Duration.ofSeconds(10),
                LiveActivityPushService.DEFAULT_APNS_TOPIC, null);
        service.setCheckInRequestCollaborators(groups, policy);

        Group g = new Group();
        g.setGroupId("hh-1");
        g.setGroupName("Plouis household");
        g.setGroupType("Household");
        g.setAlert("Active");
        g.setAlertActivatedAt(Instant.now().minus(Duration.ofMinutes(1)));
        g.setMemberEmails(List.of("Dione@x.com", "maya@x.com", "ben@x.com"));
        when(groups.findByGroupId("hh-1")).thenReturn(Optional.of(g));
        when(tokens.findLiveUpdateTokens(eq("situation"), anyString())).thenReturn(List.of());
    }

    private static LiveActivityToken token(Long id, String email, String type, String ref) {
        LiveActivityToken t = new LiveActivityToken();
        t.setId(id);
        t.setUserEmail(email);
        t.setKind("checkin_request");
        t.setTokenType(type);
        t.setReferenceId(ref);
        t.setApnsToken("abcdef012345678" + id);
        t.setFcmToken("fcm-" + id);
        return t;
    }

    private static JsonNode aps(Message m) throws Exception {
        return JSON.readTree(GsonFactory.getDefaultInstance().toString(m)).path("apns").path("payload").path("aps");
    }

    @Test
    void aCheckInStartPushesTheCardToEveryOtherMemberThePolicyWouldInterrupt() throws Exception {
        when(tokens.findLivePushToStart(eq("checkin_request"), anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> emails = inv.getArgument(1);
            // The starter is never asked: their address must not even be looked up.
            assertThat(emails).containsExactlyInAnyOrder("maya@x.com", "ben@x.com");
            return List.of(token(11L, "maya@x.com", "push_to_start", null),
                    token(12L, "ben@x.com", "push_to_start", null));
        });
        when(policy.wouldPush("maya@x.com", PushPolicyService.Category.GROUP_ALERT_HOUSEHOLD, null)).thenReturn(true);
        when(policy.wouldPush("ben@x.com", PushPolicyService.Category.GROUP_ALERT_HOUSEHOLD, null)).thenReturn(false);

        service.situationStarted("hh-1", STARTER);

        assertThat(sent).hasSize(1);
        JsonNode aps = aps(sent.get(0));
        assertThat(aps.path("event").asText()).isEqualTo("start");
        assertThat(aps.path("attributes-type").asText()).isEqualTo("CheckInRequestAttributes");
        assertThat(aps.path("attributes").path("groupId").asText()).isEqualTo("hh-1");
        assertThat(aps.path("attributes").path("groupName").asText()).isEqualTo("Plouis household");
        assertThat(aps.path("attributes").path("scope").asText()).isEqualTo("household");
        assertThat(aps.path("attributes").path("deepLink").asText()).contains("hh-1");
        assertThat(aps.path("attributes").path("askedAtEpoch").isNumber()).isTrue();
        assertThat(aps.path("content-state").path("phase").asText()).isEqualTo("asked");
        // D6: Apple requires the alert, but it is silent beside the GROUP_ALERT push.
        assertThat(aps.path("alert").path("body").asText()).isEqualTo("You were asked to check in.");
        assertThat(aps.path("alert").has("sound")).isFalse();
    }

    @Test
    void noCollaboratorsMeansNoPushToStart() {
        LiveActivityPushService bare = new LiveActivityPushService(tokens, () -> mock(LiveActivityStateBuilder.class),
                e -> false, message -> { sent.add(message); return "x"; }, (task, delay) -> { }, Runnable::run,
                Duration.ofSeconds(10), LiveActivityPushService.DEFAULT_APNS_TOPIC, null);
        bare.situationStarted("hh-1", STARTER);
        assertThat(sent).isEmpty();
        verify(tokens, never()).findLivePushToStart(anyString(), anyCollection());
    }

    @Test
    void anAnswerFromAnySurfaceEndsThatMembersCardAsSent() throws Exception {
        when(tokens.findLiveUpdateTokensForUser("checkin_request", "maya@x.com"))
                .thenReturn(List.of(token(21L, "maya@x.com", "update", "hh-1")));
        service.memberAnswered("Maya@x.com", "SAFE");
        assertThat(sent).hasSize(1);
        JsonNode aps = aps(sent.get(0));
        assertThat(aps.path("event").asText()).isEqualTo("end");
        assertThat(aps.path("content-state").path("phase").asText()).isEqualTo("sent");
        assertThat(aps.path("content-state").path("status").asText()).isEqualTo("SAFE");
        assertThat(aps.has("dismissal-date")).isTrue();
        verify(tokens).endByIds(eq(List.of(21L)), any());
    }

    @Test
    void theCheckInEndingEndsTheRequestCardsToo() throws Exception {
        when(tokens.findLiveUpdateTokens("checkin_request", "hh-1"))
                .thenReturn(List.of(token(31L, "ben@x.com", "update", "hh-1")));
        service.situationEnded("hh-1", Instant.now().minus(Duration.ofMinutes(5)),
                LiveActivityStateBuilder.REASON_MANUAL, STARTER);
        assertThat(sent).hasSize(1);
        JsonNode aps = aps(sent.get(0));
        assertThat(aps.path("event").asText()).isEqualTo("end");
        assertThat(aps.path("content-state").path("phase").asText()).isEqualTo("ended");
        assertThat(aps.path("content-state").path("note").asText()).isEqualTo("The check-in ended.");
        verify(tokens).endByIds(eq(List.of(31L)), any());
    }
}
