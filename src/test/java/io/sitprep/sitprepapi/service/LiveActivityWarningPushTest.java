package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.messaging.Message;
import io.sitprep.sitprepapi.domain.LiveActivityToken;
import io.sitprep.sitprepapi.repo.LiveActivityTokenRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P3b (EXEC-P3b-official-warning.md): what the official warning card sends.
 * Start is silent push-to-start for people holding no warning card; a new
 * revision updates the thread's cards in place, once; a more severe warning
 * takes the one card (W3); an end carries the server's words.
 */
class LiveActivityWarningPushTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String THREAD = "VTEC:KSLC.FF.W.0042";
    private static final Instant ENDS = Instant.now().plus(Duration.ofHours(2));

    private LiveActivityTokenRepo tokens;
    private final List<Message> sent = new ArrayList<>();
    private LiveActivityPushService service;

    @BeforeEach
    void setUp() {
        tokens = mock(LiveActivityTokenRepo.class);
        service = new LiveActivityPushService(tokens, () -> null, e -> false,
                message -> { sent.add(message); return "projects/x/messages/1"; },
                (task, delay) -> { }, Runnable::run, Duration.ofSeconds(10),
                LiveActivityPushService.DEFAULT_APNS_TOPIC, null);
    }

    private static LiveActivityToken token(Long id, String email, String type, String ref) {
        LiveActivityToken t = new LiveActivityToken();
        t.setId(id);
        t.setUserEmail(email);
        t.setKind(LiveActivityToken.KIND_OFFICIAL_WARNING);
        t.setTokenType(type);
        t.setReferenceId(ref);
        t.setApnsToken("abcdef012345678" + id);
        t.setFcmToken("fcm-" + id);
        return t;
    }

    private static LiveActivityPushService.WarningCard card(String thread, String revision, int rank, Instant ends) {
        return new LiveActivityPushService.WarningCard(thread, revision, "Flash Flood Warning",
                "National Weather Service", "/hazards?alert=NWS-x", rank, ends,
                "Move to higher ground now.", "SitPrep reviewed guidance");
    }

    private static JsonNode aps(Message m) throws Exception {
        return JSON.readTree(GsonFactory.getDefaultInstance().toString(m)).path("apns").path("payload").path("aps");
    }

    @Test
    void startsSilentlyForPeopleWithNoWarningCard_withTheAttributesTypeAndTheAttributedActionLine() throws Exception {
        when(tokens.findLiveUpdateTokens(LiveActivityToken.KIND_OFFICIAL_WARNING, THREAD)).thenReturn(List.of());
        when(tokens.findLiveUpdateTokensForUsers(eq(LiveActivityToken.KIND_OFFICIAL_WARNING), anyCollection())).thenReturn(List.of());
        when(tokens.findLivePushToStart(eq(LiveActivityToken.KIND_OFFICIAL_WARNING), anyCollection()))
                .thenReturn(List.of(token(1L, "inside@x", "push_to_start", null)));

        service.warningIssued(card(THREAD, "r1", 1, ENDS), List.of("Inside@X"), true);

        assertThat(sent).hasSize(1);
        JsonNode aps = aps(sent.get(0));
        assertThat(aps.path("event").asText()).isEqualTo("start");
        assertThat(aps.path("attributes-type").asText()).isEqualTo("OfficialWarningAttributes");
        assertThat(aps.path("attributes").path("threadKey").asText()).isEqualTo(THREAD);
        assertThat(aps.path("attributes").path("atHome").asBoolean()).isTrue();
        assertThat(aps.path("content-state").path("actionText").asText()).isEqualTo("Move to higher ground now.");
        assertThat(aps.path("content-state").path("actionAttribution").asText()).isEqualTo("SitPrep reviewed guidance");
        assertThat(aps.path("content-state").path("endsAtEpoch").asLong()).isEqualTo(ENDS.getEpochSecond());
        assertThat(aps.path("stale-date").asLong()).isEqualTo(ENDS.getEpochSecond());
        assertThat(aps.path("alert").has("sound")).isFalse();   // the warning push carries the sound
        verify(tokens).findLivePushToStart(eq(LiveActivityToken.KIND_OFFICIAL_WARNING),
                argThat(c -> c.size() == 1 && c.contains("inside@x")));
    }

    @Test
    void aNewRevisionUpdatesTheThreadsCardsOnce_andSaysExtendedWhenTheEndMovedLater() throws Exception {
        LiveActivityToken following = token(2L, "inside@x", "update", THREAD);
        when(tokens.findLiveUpdateTokens(LiveActivityToken.KIND_OFFICIAL_WARNING, THREAD)).thenReturn(List.of(following));
        when(tokens.findLiveUpdateTokensForUsers(eq(LiveActivityToken.KIND_OFFICIAL_WARNING), anyCollection()))
                .thenReturn(List.of(following));

        service.warningIssued(card(THREAD, "r1", 1, ENDS), List.of("inside@x"), false);
        service.warningIssued(card(THREAD, "r1", 1, ENDS), List.of("inside@x"), true);   // the at-home half: same revision
        service.warningIssued(card(THREAD, "r2", 1, ENDS.plus(Duration.ofHours(1))), List.of("inside@x"), false);

        assertThat(sent).hasSize(2);   // r1 once, r2 once; never a second start for a follower
        JsonNode second = aps(sent.get(1));
        assertThat(second.path("event").asText()).isEqualTo("update");
        assertThat(second.path("content-state").path("extended").asBoolean()).isTrue();
        verify(tokens, never()).findLivePushToStart(any(), anyCollection());
    }

    @Test
    void aMoreSevereWarningTakesTheOneCard_aLessSevereOneDoesNot() throws Exception {
        String severeThread = "VTEC:KSLC.SV.W.0001";
        LiveActivityToken onSevere = token(3L, "inside@x", "update", severeThread);
        when(tokens.findLiveUpdateTokens(eq(LiveActivityToken.KIND_OFFICIAL_WARNING), anyString())).thenReturn(List.of());
        when(tokens.findLiveUpdateTokensForUsers(eq(LiveActivityToken.KIND_OFFICIAL_WARNING), anyCollection()))
                .thenReturn(List.of(onSevere));
        when(tokens.findLivePushToStart(eq(LiveActivityToken.KIND_OFFICIAL_WARNING), anyCollection()))
                .thenReturn(List.of(token(4L, "inside@x", "push_to_start", null)));

        service.warningIssued(card(severeThread, "s1", 1, ENDS), List.of(), false);   // records its rank
        service.warningIssued(card("VTEC:KSLC.FF.W.0099", "f1", 1, ENDS), List.of("inside@x"), false);
        assertThat(sent).isEmpty();   // equally severe: push only, the card stays

        service.warningIssued(card("VTEC:KSLC.TO.W.0007", "t1", 2, ENDS), List.of("inside@x"), false);
        assertThat(sent).hasSize(2);
        assertThat(aps(sent.get(0)).path("event").asText()).isEqualTo("end");
        assertThat(aps(sent.get(0)).path("content-state").path("endReason").asText()).isEqualTo("outranked");
        assertThat(aps(sent.get(1)).path("event").asText()).isEqualTo("start");
        verify(tokens).endByIds(eq(List.of(3L)), any());
    }

    @Test
    void anEndCarriesTheIssuersWordsAndRetiresTheTokens() throws Exception {
        when(tokens.findLiveUpdateTokens(LiveActivityToken.KIND_OFFICIAL_WARNING, THREAD))
                .thenReturn(List.of(token(5L, "inside@x", "update", THREAD)));

        service.warningEnded(THREAD, LiveActivityPushService.WARNING_END_CANCELLED,
                "The National Weather Service has cancelled the Flash Flood Warning.");

        JsonNode aps = aps(sent.get(0));
        assertThat(aps.path("event").asText()).isEqualTo("end");
        assertThat(aps.path("content-state").path("phase").asText()).isEqualTo("ended");
        assertThat(aps.path("content-state").path("note").asText()).startsWith("The National Weather Service has cancelled");
        assertThat(aps.path("content-state").path("actionText").isMissingNode()).isTrue();   // null keys are dropped on the wire
        assertThat(aps.has("dismissal-date")).isTrue();
        verify(tokens).endByIds(eq(List.of(5L)), any());
    }

    @Test
    void longIssuerTextIsClampedForThePayload() {
        String clamped = LiveActivityPushService.clampText("x".repeat(1000));
        assertThat(clamped).hasSizeLessThanOrEqualTo(LiveActivityPushService.MAX_WARNING_TEXT);
        assertThat(LiveActivityPushService.clampText("  ")).isNull();
        assertThat(Set.of("a")).isNotEmpty();
    }
}
