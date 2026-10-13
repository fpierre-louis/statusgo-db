package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseException;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import io.sitprep.sitprepapi.domain.LiveActivityToken;
import io.sitprep.sitprepapi.dto.CheckInRollupDto;
import io.sitprep.sitprepapi.repo.LiveActivityTokenRepo;
import io.sitprep.sitprepapi.service.LiveActivityStateBuilder.Situation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * live_activities GAMEPLAN §3.1 / §3.4: what reaches FCM, when, and how loud.
 * The scheduler is captured and the executor runs inline, so each test drives
 * the coalescing window by hand.
 */
class LiveActivityPushServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.now().minus(Duration.ofMinutes(17));
    private static final String OWNER = "dione@x.com";
    private static final String ADMIN = "maya@x.com";

    private LiveActivityTokenRepo tokens;
    private LiveActivityStateBuilder builder;
    private final List<Message> sent = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final Set<String> silencedEmails = new HashSet<>();
    private FirebaseMessagingException failNext;
    private LiveActivityPushService service;

    @BeforeEach
    void setUp() {
        tokens = mock(LiveActivityTokenRepo.class);
        builder = mock(LiveActivityStateBuilder.class);
        service = new LiveActivityPushService(tokens, () -> builder, silencedEmails::contains,
                message -> {
                    sent.add(message);
                    if (failNext != null) {
                        FirebaseMessagingException e = failNext;
                        failNext = null;
                        throw e;
                    }
                    return "projects/x/messages/1";
                },
                (task, delay) -> scheduled.add(task), Runnable::run, Duration.ofSeconds(10),
                LiveActivityPushService.DEFAULT_APNS_TOPIC, null);
        when(tokens.existsLiveUpdateToken("situation", "hh-1")).thenReturn(true);
        when(tokens.findLiveUpdateTokens("situation", "hh-1")).thenReturn(List.of(token(1L, OWNER), token(2L, ADMIN)));
    }

    private static LiveActivityToken token(Long id, String email) {
        LiveActivityToken t = new LiveActivityToken();
        t.setId(id);
        t.setUserEmail(email);
        t.setKind("situation");
        t.setTokenType("update");
        t.setReferenceId("hh-1");
        t.setActivityId("act-" + id);
        t.setApnsToken("abcdef012345678" + id);
        t.setFcmToken("fcm-" + id);
        return t;
    }

    private static Situation situation(int safe, int help) {
        CheckInRollupDto r = new CheckInRollupDto("hh-1", "Plouis household", true, START, 6, safe + help,
                safe, help, 0, 6 - safe - help, List.of(), START.plus(Duration.ofHours(48)));
        return new Situation("hh-1", "Plouis household", "Household", r, START,
                START.plus(Duration.ofHours(48)), false, null);
    }

    private void running(int safe, int help) {
        when(builder.currentSituation("hh-1")).thenReturn(Optional.of(situation(safe, help)));
    }

    private static JsonNode wire(Message m) throws Exception {
        return JSON.readTree(GsonFactory.getDefaultInstance().toString(m));
    }

    private static JsonNode aps(Message m) throws Exception {
        return wire(m).path("apns").path("payload").path("aps");
    }

    private static String priority(Message m) throws Exception {
        return wire(m).path("apns").path("headers").path("apns-priority").asText();
    }

    private void flush() {
        List<Runnable> due = new ArrayList<>(scheduled);
        scheduled.clear();
        due.forEach(Runnable::run);
    }

    private static FirebaseMessagingException fcmError(MessagingErrorCode code) throws Exception {
        Method m = FirebaseMessagingException.class.getDeclaredMethod(
                "withMessagingErrorCode", FirebaseException.class, MessagingErrorCode.class);
        m.setAccessible(true);
        return (FirebaseMessagingException) m.invoke(null,
                new FirebaseException(ErrorCode.NOT_FOUND, "gone", null), code);
    }

    // ── updates ────────────────────────────────────────────────────────

    @Test
    void manyChangesInOneWindowAreOneQuietUpdatePerCard() throws Exception {
        running(3, 0);
        service.situationChanged("hh-1");
        service.situationChanged("hh-1");
        service.situationChanged("hh-1");
        assertThat(scheduled).hasSize(1);
        assertThat(sent).isEmpty();   // nothing goes out before the window closes

        flush();

        assertThat(sent).hasSize(2);
        for (Message m : sent) {
            assertThat(priority(m)).isEqualTo("5");
            assertThat(aps(m).path("event").asText()).isEqualTo("update");
            assertThat(aps(m).has("alert")).isFalse();
            assertThat(aps(m).path("content-state").path("accounted").asInt()).isEqualTo(3);
            long ts = aps(m).path("timestamp").asLong();
            assertThat(aps(m).path("stale-date").asLong()).isEqualTo(ts + 600);
        }
        assertThat(wire(sent.get(0)).path("token").asText()).isEqualTo("fcm-1");
        assertThat(wire(sent.get(1)).path("apns").path("live_activity_token").asText()).isEqualTo("abcdef0123456782");
        verify(tokens, never()).endByIds(any(), any());
    }

    @Test
    void theFirstUpdateSetsTheBaseline_andNeverAlerts() throws Exception {
        running(3, 1);   // someone already needs help when the card starts
        service.situationChanged("hh-1");
        flush();
        assertThat(sent).allSatisfy(m -> {
            try {
                assertThat(priority(m)).isEqualTo("5");
                assertThat(aps(m).has("alert")).isFalse();
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test
    void aRiseInHelpIsPriority10WithAnAlert_exceptWhereSoundIsSuppressed() throws Exception {
        running(3, 0);
        service.situationChanged("hh-1");
        flush();
        sent.clear();

        silencedEmails.add(ADMIN);   // a lockdown hazard where Maya is
        running(3, 1);
        service.situationChanged("hh-1");
        flush();

        Message toOwner = sent.get(0);
        Message toAdmin = sent.get(1);
        assertThat(priority(toOwner)).isEqualTo("10");
        assertThat(aps(toOwner).path("alert").path("title").asText()).isEqualTo("Plouis household");
        assertThat(aps(toOwner).path("alert").path("body").asText()).isEqualTo("1 needs help · 4 of 6 accounted for");
        assertThat(aps(toOwner).path("relevance-score").asInt()).isEqualTo(100);
        assertThat(aps(toAdmin).has("alert")).as("no sound, no lit screen in a lockdown").isFalse();

        // Same count again: back to quiet.
        sent.clear();
        service.situationChanged("hh-1");
        flush();
        assertThat(priority(sent.get(0))).isEqualTo("5");
        assertThat(aps(sent.get(0)).has("alert")).isFalse();
    }

    @Test
    void noCardsOrNoRunningCheckIn_nothingIsSent() {
        when(tokens.existsLiveUpdateToken("situation", "hh-1")).thenReturn(false);
        service.situationChanged("hh-1");
        flush();
        verifyNoInteractions(builder);

        when(tokens.existsLiveUpdateToken("situation", "hh-1")).thenReturn(true);
        when(builder.currentSituation("hh-1")).thenReturn(Optional.empty());
        service.situationChanged("hh-1");
        flush();
        assertThat(sent).isEmpty();
    }

    @Test
    void anUnregisteredTokenIsRetired_andTheOthersStillGetTheirs() throws Exception {
        running(3, 0);
        failNext = fcmError(MessagingErrorCode.UNREGISTERED);
        service.situationChanged("hh-1");
        flush();

        assertThat(sent).hasSize(2);
        verify(tokens).endByIds(eq(List.of(1L)), any());
        verify(tokens, never()).endByIds(eq(List.of(2L)), any());
    }

    @Test
    void aTransientFcmErrorKeepsTheToken() throws Exception {
        running(3, 0);
        failNext = fcmError(MessagingErrorCode.UNAVAILABLE);
        service.situationChanged("hh-1");
        flush();
        verify(tokens, never()).endByIds(any(), any());
    }

    @Test
    void hooksNeverThrow_evenWhenTheWorkFails() {
        when(tokens.existsLiveUpdateToken(any(), any())).thenThrow(new IllegalStateException("db down"));
        when(tokens.findLiveUpdateTokens(any(), any())).thenThrow(new IllegalStateException("db down"));
        when(tokens.endLiveUpdateTokens(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        service.situationChanged("hh-1");
        flush();
        service.situationStarted("hh-1");
        service.situationEnded("hh-1", START, "manual", OWNER);
        service.situationChanged(null);
        service.situationEnded(null, null, null, null);
    }

    // ── start and end ──────────────────────────────────────────────────

    @Test
    void aNewCheckInRetiresTokensFromAnEarlierOne() {
        service.situationStarted("hh-1");
        verify(tokens).endLiveUpdateTokens(eq("situation"), eq("hh-1"), any());
    }

    @Test
    void anEndSendsTheFinalCardToEachViewer_inTheirOwnWords_thenRetiresTheTokens() throws Exception {
        running(3, 0);
        service.situationChanged("hh-1");   // pending update the end must cancel
        when(builder.endedSituation("hh-1", START)).thenReturn(Optional.of(situation(6, 0)));

        service.situationEnded("hh-1", START, LiveActivityStateBuilder.REASON_MANUAL, OWNER);

        assertThat(sent).hasSize(2);
        Message toOwner = sent.get(0);
        Message toAdmin = sent.get(1);
        for (Message m : sent) {
            assertThat(priority(m)).isEqualTo("10");
            assertThat(aps(m).path("event").asText()).isEqualTo("end");
            long ts = aps(m).path("timestamp").asLong();
            assertThat(aps(m).path("dismissal-date").asLong()).isEqualTo(ts + 900);
            assertThat(aps(m).path("content-state").path("phase").asText()).isEqualTo("ended");
            assertThat(aps(m).path("content-state").path("accounted").asInt()).isEqualTo(6);
        }
        assertThat(aps(toOwner).path("content-state").path("note").asText()).isEqualTo("Ended by you after 17 min.");
        assertThat(aps(toOwner).has("alert")).as("your own tap needs no chime").isFalse();
        assertThat(aps(toAdmin).path("content-state").path("note").asText()).isEqualTo("Ended after 17 min.");
        assertThat(aps(toAdmin).path("alert").path("body").asText()).isEqualTo("Check-in ended · 6 of 6 accounted for");
        verify(tokens).endByIds(eq(List.of(1L, 2L)), any());

        // The cancelled update never fires on top of the ended card.
        sent.clear();
        flush();
        assertThat(sent).isEmpty();
    }

    @Test
    void aDecayEndSaysSo_andAlertsNobodyInALockdown() throws Exception {
        silencedEmails.add(OWNER);
        when(builder.endedSituation("hh-1", START)).thenReturn(Optional.of(situation(4, 0)));
        service.situationEnded("hh-1", START, LiveActivityStateBuilder.REASON_DECAY, null);

        assertThat(aps(sent.get(0)).path("content-state").path("note").asText()).startsWith("Ended automatically after");
        assertThat(aps(sent.get(0)).has("alert")).isFalse();
        assertThat(aps(sent.get(1)).has("alert")).isTrue();
    }

    @Test
    void anEndKeepsThePlanVariantTheLastUpdateShowed() throws Exception {
        Situation plan = new Situation("hh-1", "Plouis household", "Household", situation(4, 0).rollup(),
                START, START.plus(Duration.ofHours(48)), true, 2);
        when(builder.currentSituation("hh-1")).thenReturn(Optional.of(plan));
        service.situationChanged("hh-1");
        flush();
        sent.clear();
        // The all-clear ended the plan in the same transaction: "now" reads no plan.
        when(builder.endedSituation("hh-1", START)).thenReturn(Optional.of(situation(6, 0)));

        service.situationEnded("hh-1", START, "manual", OWNER);

        assertThat(aps(sent.get(0)).path("content-state").path("plan").asBoolean()).isTrue();
        assertThat(aps(sent.get(0)).path("content-state").path("pickup").asInt()).isEqualTo(2);
    }

    @Test
    void anEndWithNoHonestNumbersRetiresTheTokensWithoutSending() {
        when(builder.endedSituation("hh-1", START)).thenReturn(Optional.empty());
        service.situationEnded("hh-1", START, "manual", OWNER);
        assertThat(sent).isEmpty();
        verify(tokens).endByIds(eq(List.of(1L, 2L)), any());
    }

    @Test
    void aSituationTokenRegistrationQueuesOneServerUpdate() {
        service.tokenRegistered(token(9L, OWNER));
        assertThat(scheduled).hasSize(1);
        LiveActivityToken share = token(10L, OWNER);
        share.setKind("location_share");
        service.tokenRegistered(share);
        assertThat(scheduled).hasSize(1);   // location share is local in P1
    }
}
