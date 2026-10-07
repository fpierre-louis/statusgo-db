package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.notifications.NotificationPresentationBuilder;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Quiet hours vs the inbox, on the hazard path (release audit 2026-10-07).
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code pushSevereAlert} evaluated {@link PushPolicyService} per nearby user
 * and kept only Lane A. When quiet hours or the rate cap demoted a non-critical
 * hazard (a Fire Warning, a warning NWS rated below Severe) to Lane B, the user
 * got neither a push nor an inbox row — the warning disappeared, while a
 * deferred DM or check-in request through {@code deliverPresenceAware} kept its
 * Lane B row. Now Lane B gets the same inbox row as a push, with no FCM.</p>
 *
 * <p>Real {@link PushPolicyService} over a mocked preference repo (the lane
 * authority under test), mocked {@link NotificationService} for the routing
 * tests, and a real {@link NotificationService} for the row-shape tests.</p>
 */
class HazardQuietHoursInboxTest {

    private static AlertDispatchService templates;

    private UserAlertPreferenceRepo prefRepo;
    private RateLimiterService rateLimiter;
    private NotificationService notifications;
    private AlertDispatchService dispatcher;
    private final Map<String, UserAlertPreference> prefs = new HashMap<>();

    /** Somewhere in Arizona; every recipient sits on top of it. */
    private static final double LAT = 33.3, LNG = -110.5;
    private static final double[] COORD = {LNG, LAT};

    @BeforeAll
    static void loadTemplates() {
        templates = new AlertDispatchService(null, null, null, null, null, null, null, null);
        templates.loadTemplates();
    }

    @BeforeEach
    void setUp() {
        prefRepo = mock(UserAlertPreferenceRepo.class);
        rateLimiter = mock(RateLimiterService.class);
        when(rateLimiter.tryConsume(any(), any())).thenReturn(true);
        when(prefRepo.findByEmail(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(prefs.get((String) inv.getArgument(0))));
        when(prefRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PushPolicyService policy = new PushPolicyService(prefRepo, rateLimiter);

        notifications = mock(NotificationService.class);
        dispatcher = new AlertDispatchService(null, null, null, null, null, notifications, null, policy);
        dispatcher.loadTemplates();
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private UserInfo user(String email, Consumer<UserAlertPreference> pref) {
        UserAlertPreference p = new UserAlertPreference();
        p.setUserEmail(email);
        pref.accept(p);
        prefs.put(email, p);
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setFcmtoken("tok-" + email);
        u.setLastKnownLat(LAT);
        u.setLastKnownLng(LNG);
        return u;
    }

    /** Quiet window centred on now (UTC), so the test is in-window at any hour. */
    private static void quietNow(UserAlertPreference p) {
        LocalTime now = LocalTime.now(ZoneOffset.UTC);
        p.setQuietHoursEnabled(true);
        p.setTimezone("UTC");
        p.setQuietStart(now.minusHours(2));
        p.setQuietEnd(now.plusHours(2));
    }

    private static void none(UserAlertPreference p) {}

    private DispatchTemplate tpl(NormalizedAlert a) {
        return templates.matchForAlert(a).orElseThrow();
    }

    private void dispatch(NormalizedAlert a, List<UserInfo> candidates) {
        DispatchTemplate t = tpl(a);
        dispatcher.pushSevereAlert(a, t, AlertSafetyPolicy.evaluate(a, t), COORD, candidates);
    }

    @SuppressWarnings("unchecked")
    private List<UserInfo> pushed() {
        ArgumentCaptor<List<UserInfo>> c = ArgumentCaptor.forClass(List.class);
        verify(notifications).sendHazardAlertBatch(c.capture(), anyString(), anyString(), anyString(),
                anyString(), any(), anyBoolean());
        return c.getValue();
    }

    /** Emails the last {@link #inboxed} call marked as held by quiet hours (EXEC-N). */
    private java.util.Set<String> quietMarked = java.util.Set.of();

    @SuppressWarnings("unchecked")
    private List<UserInfo> inboxed(Category expectedCategory) {
        ArgumentCaptor<List<UserInfo>> c = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<java.util.Set<String>> quiet = ArgumentCaptor.forClass(java.util.Set.class);
        verify(notifications).logHazardAlertInboxOnly(c.capture(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(expectedCategory), quiet.capture());
        quietMarked = quiet.getValue();
        return c.getValue();
    }

    private static List<String> emails(List<UserInfo> us) {
        return us.stream().map(UserInfo::getUserEmail).toList();
    }

    // ── quiet hours: deferred hazard → inbox row, no FCM ─────────────────

    @Test
    void aQuietHoursFireWarningLandsInTheInboxWithoutAPush() {
        NormalizedAlert fire = TestAlerts.nws("Fire Warning").build();
        assertThat(AlertSafetyPolicy.evaluate(fire, tpl(fire)).criticalPush())
                .as("Fire Warning really reaches the push path").isTrue();

        dispatch(fire, List.of(user("night@x.com", HazardQuietHoursInboxTest::quietNow)));

        assertThat(emails(inboxed(Category.WILDFIRE_NEAR))).containsExactly("night@x.com");
        assertThat(quietMarked).as("marked for the morning catch-up").containsExactly("night@x.com");
        verify(notifications, never()).sendHazardAlertBatch(anyList(), any(), any(), any(), any(), any(),
                anyBoolean());
    }

    @Test
    void aQuietHoursWarningNwsRatedModerateLandsInTheInboxWithoutAPush() {
        // A critical_push template whose CAP severity is below Severe: no bypass.
        NormalizedAlert tornado = TestAlerts.nws("Tornado Warning").severity("Moderate").build();
        assertThat(AlertSafetyPolicy.evaluate(tornado, tpl(tornado)).criticalPush()).isTrue();

        dispatch(tornado, List.of(user("night@x.com", HazardQuietHoursInboxTest::quietNow)));

        assertThat(emails(inboxed(Category.NWS_SEVERE_EXTREME))).containsExactly("night@x.com");
        verify(notifications, never()).sendHazardAlertBatch(anyList(), any(), any(), any(), any(), any(),
                anyBoolean());
    }

    @Test
    void aQuietHoursSubM6QuakeLandsInTheInboxWithoutAPush() {
        // Production note: dispatchOnce reaches pushSevereAlert for M6.0+
        // quakes only (AlertDispatchService.isMajorQuakePush, EXEC-N). If a
        // sub-M6 quake ever reaches it, at night it must take the Lane B route.
        NormalizedAlert m55 = TestAlerts.usgs("M5.6 — 10 km N of Somewhere").severity("Moderate").build();

        dispatch(m55, List.of(user("night@x.com", HazardQuietHoursInboxTest::quietNow)));

        assertThat(emails(inboxed(Category.USGS_QUAKE_MAJOR))).containsExactly("night@x.com");
        verify(notifications, never()).sendHazardAlertBatch(anyList(), any(), any(), any(), any(), any(),
                anyBoolean());
    }

    // ── rate cap: deferred hazard → inbox row ────────────────────────────

    @Test
    void aRateCappedHazardRecipientGetsAnInboxRow() {
        when(rateLimiter.tryConsume(eq("capped@x.com"), any())).thenReturn(false);
        NormalizedAlert fire = TestAlerts.nws("Fire Warning").build();

        dispatch(fire, List.of(
                user("capped@x.com", HazardQuietHoursInboxTest::none),
                user("fresh@x.com", HazardQuietHoursInboxTest::none)));

        assertThat(emails(pushed())).containsExactly("fresh@x.com");
        assertThat(emails(inboxed(Category.WILDFIRE_NEAR))).containsExactly("capped@x.com");
        assertThat(quietMarked).as("a rate cap is not a night held by quiet hours").isEmpty();
    }

    // ── one route per recipient; push and row carry the same content ─────

    @Test
    void eachRecipientGetsExactlyOneRouteAndBothRoutesCarryTheSameNotification() {
        NormalizedAlert fire = TestAlerts.nws("Fire Warning").build();
        UserInfo day = user("day@x.com", HazardQuietHoursInboxTest::none);
        UserInfo night = user("night@x.com", HazardQuietHoursInboxTest::quietNow);
        UserInfo muted = user("muted@x.com", p -> p.setWildfires(false));
        UserInfo laneC = user("lanec@x.com", p -> { p.setPushEnabled(false); p.setInboxEnabled(false); });
        // The same person listed twice (case differs) must be evaluated once.
        UserInfo dayAgain = new UserInfo();
        dayAgain.setUserEmail("DAY@x.com");
        dayAgain.setFcmtoken("tok-again");
        dayAgain.setLastKnownLat(LAT);
        dayAgain.setLastKnownLng(LNG);

        dispatch(fire, List.of(day, night, muted, laneC, dayAgain));

        ArgumentCaptor<String> pTitle = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pBody = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pRef = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pUrl = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pData = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserInfo>> pTo = ArgumentCaptor.forClass(List.class);
        verify(notifications, times(1)).sendHazardAlertBatch(pTo.capture(), pTitle.capture(), pBody.capture(),
                pRef.capture(), pUrl.capture(), pData.capture(), anyBoolean());

        ArgumentCaptor<String> iTitle = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> iBody = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> iRef = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> iUrl = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> iData = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserInfo>> iTo = ArgumentCaptor.forClass(List.class);
        verify(notifications, times(1)).logHazardAlertInboxOnly(iTo.capture(), iTitle.capture(),
                iBody.capture(), iRef.capture(), iUrl.capture(), iData.capture(), eq(Category.WILDFIRE_NEAR),
                any());

        assertThat(emails(pTo.getValue())).as("Lane A: push (+ its own row), once").containsExactly("day@x.com");
        assertThat(emails(iTo.getValue())).as("Lane B: inbox only").containsExactly("night@x.com");
        // muted (DROP) and lanec (Lane C) appear in neither list.

        assertThat(iTitle.getValue()).isEqualTo(pTitle.getValue());
        assertThat(iBody.getValue()).isEqualTo(pBody.getValue());
        assertThat(iRef.getValue()).isEqualTo(pRef.getValue()).isEqualTo("NWS-id-Fire Warning");
        assertThat(iUrl.getValue()).isEqualTo(pUrl.getValue()).isEqualTo("/hazards");
        assertThat(iData.getValue()).isEqualTo(pData.getValue()).isNotBlank();
        verify(rateLimiter, times(1)).tryConsume(eq("day@x.com"), any());
    }

    @Test
    void anOptedOutRecipientGetsNothingEvenAtNight() {
        NormalizedAlert fire = TestAlerts.nws("Fire Warning").build();

        dispatch(fire, List.of(user("muted@x.com", p -> { quietNow(p); p.setWildfires(false); })));

        verifyNoInteractions(notifications);
    }

    @Test
    void aSevereWarningAtNightIsStillPushedNotInboxed() {
        NormalizedAlert tornado = TestAlerts.nws("Tornado Warning").severity("Extreme").build();

        dispatch(tornado, List.of(user("night@x.com", HazardQuietHoursInboxTest::quietNow)));

        assertThat(emails(pushed())).containsExactly("night@x.com");
        verify(notifications, never()).logHazardAlertInboxOnly(anyList(), any(), any(), any(), any(), any(),
                any(), any());
    }

    // ── the row itself: same shape as the pushed row, no FCM ─────────────

    @Test
    void theInboxOnlyRowMatchesThePushedRowAndCarriesLaneB() {
        NotificationLogRepo logRepo = mock(NotificationLogRepo.class);
        when(logRepo.save(any(NotificationLog.class))).thenAnswer(inv -> inv.getArgument(0));
        WebSocketMessageSender ws = mock(WebSocketMessageSender.class);
        WebSocketPresenceService presence = mock(WebSocketPresenceService.class);
        PushPolicyService policy = mock(PushPolicyService.class);
        NotificationService real = new NotificationService(ws, mock(UserInfoRepo.class), logRepo, presence,
                policy, mock(GroupMuteService.class));
        ReflectionTestUtils.setField(real, "presentationBuilder", new NotificationPresentationBuilder(
                mock(UserInfoRepo.class), mock(GroupRepo.class), mock(GroupPostRepo.class), mock(PostRepo.class)));

        String data = "{\"source\":\"NWS\",\"event\":\"Fire Warning\",\"severity\":\"Severe\"}";

        // The pushed row: a Lane A recipient with no token takes the batch's
        // no-FCM branch, which writes the same row a delivered push does.
        UserInfo noToken = new UserInfo();
        noToken.setUserEmail("a@x.com");
        real.sendHazardAlertBatch(List.of(noToken), "Fire Warning", "Leave now.", "NWS-1", "/hazards", data, false);

        UserInfo night = new UserInfo();
        night.setUserEmail("b@x.com");
        night.setFcmtoken("tok-b");   // has a token — and still must not be pushed
        assertThat(real.logHazardAlertInboxOnly(List.of(night), "Fire Warning", "Leave now.", "NWS-1",
                "/hazards", data, Category.WILDFIRE_NEAR)).isEqualTo(1);

        ArgumentCaptor<NotificationLog> rows = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepo, times(2)).save(rows.capture());
        NotificationLog pushedRow = rows.getAllValues().get(0);
        NotificationLog inboxRow = rows.getAllValues().get(1);

        assertThat(inboxRow.getRecipientEmail()).isEqualTo("b@x.com");
        assertThat(inboxRow.getType()).isEqualTo(pushedRow.getType()).isEqualTo("hazard_alert");
        assertThat(inboxRow.getTitle()).isEqualTo(pushedRow.getTitle());
        assertThat(inboxRow.getBody()).isEqualTo(pushedRow.getBody());
        assertThat(inboxRow.getReferenceId()).isEqualTo(pushedRow.getReferenceId());
        assertThat(inboxRow.getTargetUrl()).isEqualTo(pushedRow.getTargetUrl());
        assertThat(inboxRow.getAdditionalData()).isEqualTo(pushedRow.getAdditionalData());
        assertThat(inboxRow.getPresentationJson())
                .as("same presentation, so the inbox renders and deep-links it like the push")
                .isNotNull()
                .isEqualTo(pushedRow.getPresentationJson());

        assertThat(inboxRow.getToken()).as("no FCM token on a Lane B row").isNull();
        assertThat(inboxRow.getLane()).isEqualTo("B");
        assertThat(inboxRow.getCategory()).isEqualTo("WILDFIRE_NEAR");
        assertThat(inboxRow.getErrorMessage()).isEqualTo(NotificationService.LANE_B_SILENT_INBOX);

        // An open inbox hears about both rows; neither Lane B recipient gets a
        // banner, and policy is not re-run (the caller already decided).
        verify(ws).sendInboxEvent(eq("b@x.com"), any(Map.class));
        verify(ws, never()).sendInAppNotification(any());
        verifyNoInteractions(policy);
    }

    @Test
    void onlyTheQuietHoursRecipientsRowIsMarkedForTheCatchUp() {
        NotificationLogRepo logRepo = mock(NotificationLogRepo.class);
        when(logRepo.save(any(NotificationLog.class))).thenAnswer(inv -> inv.getArgument(0));
        NotificationService real = new NotificationService(mock(WebSocketMessageSender.class),
                mock(UserInfoRepo.class), logRepo, mock(WebSocketPresenceService.class),
                mock(PushPolicyService.class), mock(GroupMuteService.class));
        UserInfo asleep = new UserInfo();
        asleep.setUserEmail("Night@X.com");
        UserInfo capped = new UserInfo();
        capped.setUserEmail("capped@x.com");

        real.logHazardAlertInboxOnly(List.of(asleep, capped), "Fire Warning", "Leave now.", "NWS-1",
                "/hazards", null, Category.WILDFIRE_NEAR, java.util.Set.of("night@x.com"));

        ArgumentCaptor<NotificationLog> rows = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepo, times(2)).save(rows.capture());
        assertThat(rows.getAllValues().get(0).getDeferredReason()).isEqualTo("QUIET_HOURS");
        assertThat(rows.getAllValues().get(1).getDeferredReason()).isNull();
    }
}
