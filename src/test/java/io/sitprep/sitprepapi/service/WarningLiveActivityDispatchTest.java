package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.AlertPushTargetingAuditRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P3b through the real dispatch paths (the alerts lane's ask: severe pushes
 * are rare, so the start / end paths are driven here, not by a live warning).
 * Conditions 1–4 of EXEC-P3b: the action line is the interpreter's; only
 * Lane A recipients; only Severe/Extreme NWS Warnings ending within 8 h;
 * expiry / delisting ends a thread's card unless a newer revision is listed.
 */
class WarningLiveActivityDispatchTest {

    private static final String THREAD = "VTEC:KSLC.TO.W.0001";
    private final Map<String, UserAlertPreference> prefs = new HashMap<>();
    private AlertDispatchService dispatcher;
    private LiveActivityPushService cards;
    private NotificationLogRepo deliveries;

    @BeforeEach
    void setUp() {
        UserAlertPreferenceRepo prefRepo = mock(UserAlertPreferenceRepo.class);
        when(prefRepo.findByEmail(anyString())).thenAnswer(inv -> {
            String email = inv.getArgument(0);
            return Optional.of(prefs.computeIfAbsent(email, e -> { var p = new UserAlertPreference(); p.setUserEmail(e); return p; }));
        });
        when(prefRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RateLimiterService rate = mock(RateLimiterService.class);
        when(rate.tryConsume(any(), any())).thenReturn(true);

        UserInfoRepo users = mock(UserInfoRepo.class);
        when(users.findByUserEmailLowerIn(any())).thenReturn(List.of());
        GroupRepo groups = mock(GroupRepo.class);
        dispatcher = new AlertDispatchService(null, null, null, users, null, mock(NotificationService.class), null,
                new PushPolicyService(prefRepo, rate));
        dispatcher.loadTemplates();
        dispatcher.setTargetingSupport(groups, mock(AlertPushTargetingAuditRepo.class));
        cards = mock(LiveActivityPushService.class);
        dispatcher.setLiveActivities(cards);
        deliveries = mock(NotificationLogRepo.class);
        dispatcher.setDeliveryRecords(deliveries, null, null);
    }

    private static String iso(Duration fromNow) {
        return Instant.now().plus(fromNow).toString();
    }

    private static NotificationService.AlertRowRefs refs() {
        return new NotificationService.AlertRowRefs("NWS:id-Tornado Warning", "rev-1", THREAD, null);
    }

    private void push(NormalizedAlert a, NotificationService.AlertRowRefs refs, UserInfo... people) {
        DispatchTemplate tpl = dispatcher.matchForAlert(a).orElseThrow();
        dispatcher.pushSevereAlert(a, tpl, AlertSafetyPolicy.evaluate(a, tpl), PushTargetingTest.COORD,
                List.of(people), refs);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aSevereWarningStartsTheCardForTheLaneAPeopleOnly_withTheInterpretersAttributedAction() {
        NormalizedAlert tornado = TestAlerts.nws("Tornado Warning").response("Shelter")
                .geometry(PushTargetingTest.SQUARE).endsAt(iso(Duration.ofHours(1))).build();
        UserInfo inside = PushTargetingTest.at("inside@x", 40.40, -111.85);
        UserInfo optedOut = PushTargetingTest.at("quiet@x", 40.41, -111.84);
        prefs.computeIfAbsent("quiet@x", e -> { var p = new UserAlertPreference(); p.setUserEmail(e); return p; })
                .setNwsAlerts(false);

        push(tornado, refs(), inside, optedOut);

        ArgumentCaptor<LiveActivityPushService.WarningCard> card = ArgumentCaptor.forClass(LiveActivityPushService.WarningCard.class);
        ArgumentCaptor<Collection<String>> to = ArgumentCaptor.forClass(Collection.class);
        verify(cards).warningIssued(card.capture(), to.capture(), eq(false));
        assertThat(to.getValue()).containsExactly("inside@x");
        LiveActivityPushService.WarningCard c = card.getValue();
        assertThat(c.threadKey()).isEqualTo(THREAD);
        assertThat(c.event()).isEqualTo("Tornado Warning");
        assertThat(c.issuer()).isEqualTo("National Weather Service");
        assertThat(c.severityRank()).isEqualTo(1);
        assertThat(c.deepLink()).startsWith("/hazards?alert=");
        assertThat(c.actionText()).isNotBlank();
        assertThat(c.actionAttribution()).isIn("SitPrep reviewed guidance", "From the National Weather Service");
    }

    @Test
    void noCardForAWatch_aWarningEndingBeyondEightHours_orAPushWithNoThread() {
        UserInfo inside = PushTargetingTest.at("inside@x", 40.40, -111.85);
        NormalizedAlert longOne = TestAlerts.nws("Tornado Warning").response("Shelter")
                .geometry(PushTargetingTest.SQUARE).endsAt(iso(Duration.ofHours(10))).build();
        push(longOne, refs(), inside);
        NormalizedAlert soon = TestAlerts.nws("Tornado Warning").response("Shelter")
                .geometry(PushTargetingTest.SQUARE).endsAt(iso(Duration.ofHours(1))).build();
        push(soon, null, inside);

        Instant now = Instant.now();
        NormalizedAlert watch = TestAlerts.nwsWatch("Tornado Watch").endsAt(iso(Duration.ofHours(1))).build();
        assertThat(AlertDispatchService.warningCardFor(watch, null, null, refs(), now)).isNull();
        NormalizedAlert moderate = TestAlerts.nws("Flood Warning").severity("Moderate").endsAt(iso(Duration.ofHours(1))).build();
        assertThat(AlertDispatchService.warningCardFor(moderate, null, null, refs(), now)).isNull();

        verify(cards, never()).warningIssued(any(), any(), anyBoolean());
    }

    @Test
    void expiryEndsTheThreadsCard_unlessANewerRevisionIsStillListed() {
        NormalizedAlert stillListed = TestAlerts.nws("Flash Flood Warning").id("newer")
                .endsAt(iso(Duration.ofHours(1))).build();
        String listedThread = AlertThreads.threadKeyOf(stillListed);
        when(deliveries.findHazardThreadKeys(eq("NWS-old"), any())).thenReturn(List.of(THREAD));
        when(deliveries.findHazardThreadKeys(eq("NWS-older"), any())).thenReturn(List.of(listedThread));
        Map<String, Boolean> cleared = new LinkedHashMap<>();
        cleared.put("NWS-old", true);
        cleared.put("NWS-older", false);
        Instant now = Instant.now();

        dispatcher.endClearedWarningCards(cleared,
                new AlertIngestService.Snapshot(List.of(stillListed), now, now), now);

        verify(cards).warningEnded(THREAD, LiveActivityPushService.WARNING_END_EXPIRED, null);
        verify(cards, never()).warningEnded(eq(listedThread), any(), any());
    }

    @Test
    void aVerifiedCancelEndsTheThreadsCardWithTheIssuersOwnWords() {
        NormalizedAlert cancel = TestAlerts.nws("Tornado Warning").id("cancel-1").messageType("Cancel")
                .description("The Tornado Warning has been cancelled. The storm has weakened.").build();

        dispatcher.deliverEnding(cancel);

        verify(cards).warningEnded(AlertThreads.threadKeyOf(cancel), LiveActivityPushService.WARNING_END_CANCELLED,
                "The Tornado Warning has been cancelled. The storm has weakened.");
    }

    @Test
    void oneCancelNamingTwoWarningsEndsBothCards() {
        var first = io.sitprep.sitprepapi.util.Vtec.parse("/O.CAN.KSLC.TO.W.0012.261020T1800Z-261020T1900Z/").orElseThrow();
        var second = io.sitprep.sitprepapi.util.Vtec.parse("/O.CAN.KSLC.TO.W.0013.261020T1800Z-261020T1900Z/").orElseThrow();
        NormalizedAlert cancel = TestAlerts.nws("Tornado Warning").id("cancel-2").messageType("Cancel")
                .vtec(List.of(first, second)).description("Both warnings are cancelled.").build();

        dispatcher.deliverEnding(cancel);

        verify(cards).warningEnded("VTEC:" + first.eventKey(), LiveActivityPushService.WARNING_END_CANCELLED,
                "Both warnings are cancelled.");
        verify(cards).warningEnded("VTEC:" + second.eventKey(), LiveActivityPushService.WARNING_END_CANCELLED,
                "Both warnings are cancelled.");
    }
}
