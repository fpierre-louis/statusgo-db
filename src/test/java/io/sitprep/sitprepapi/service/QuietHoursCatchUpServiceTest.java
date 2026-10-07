package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.config.SchedulingConfig;
import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The once-per-night "while your notifications were quiet" summary (EXEC-N).
 * Repositories mocked here; the queries themselves are exercised against H2
 * in {@code NotificationLogDeferredQueryTest}.
 */
class QuietHoursCatchUpServiceTest {

    /** 07:30 in Los Angeles (PDT, UTC-7), 2026-10-07 — 30 minutes after a 21:00–07:00 window ended. */
    private static final Instant MORNING = Instant.parse("2026-10-07T14:30:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-10-07T04:00:00Z"); // 21:00 PDT 10/6
    private static final Instant WINDOW_END = Instant.parse("2026-10-07T14:00:00Z");   // 07:00 PDT

    private NotificationLogRepo logRepo;
    private UserAlertPreferenceRepo prefRepo;
    private UserInfoRepo users;
    private NotificationService notifications;

    @BeforeEach
    void setUp() {
        logRepo = mock(NotificationLogRepo.class);
        prefRepo = mock(UserAlertPreferenceRepo.class);
        users = mock(UserInfoRepo.class);
        notifications = mock(NotificationService.class);
        when(notifications.sendQuietHoursCatchUp(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(true);
    }

    private QuietHoursCatchUpService service(Instant now) {
        return new QuietHoursCatchUpService(logRepo, prefRepo, users, notifications,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private UserAlertPreference sleeper(String email) {
        UserAlertPreference p = new UserAlertPreference();
        p.setUserEmail(email);
        p.setQuietHoursEnabled(true);
        p.setQuietStart(LocalTime.of(21, 0));
        p.setQuietEnd(LocalTime.of(7, 0));
        p.setTimezone("America/Los_Angeles");
        when(prefRepo.findByEmail(email)).thenReturn(Optional.of(p));
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setFcmtoken("tok-" + email);
        when(users.findByUserEmailIgnoreCase(email)).thenReturn(Optional.of(u));
        return p;
    }

    private void heldOvernight(String email, long count) {
        when(logRepo.countDeferredUnread(email, "QUIET_HOURS", WINDOW_START, WINDOW_END)).thenReturn(count);
    }

    // ── the window ───────────────────────────────────────────────────────

    @Test
    void theLastEndedWindowWrapsMidnightInTheUsersOwnTimezone() {
        UserAlertPreference p = new UserAlertPreference();
        p.setQuietStart(LocalTime.of(21, 0));
        p.setQuietEnd(LocalTime.of(7, 0));
        p.setTimezone("America/Los_Angeles");

        QuietHoursCatchUpService.Window w = QuietHoursCatchUpService.lastEndedWindow(p, MORNING);
        assertThat(w.start()).isEqualTo(WINDOW_START);
        assertThat(w.end()).isEqualTo(WINDOW_END);

        // At 06:59 local the window has not ended yet: the last ENDED one is yesterday's.
        QuietHoursCatchUpService.Window before = QuietHoursCatchUpService.lastEndedWindow(
                p, Instant.parse("2026-10-07T13:59:00Z"));
        assertThat(before.end()).isEqualTo(Instant.parse("2026-10-06T14:00:00Z"));
    }

    @Test
    void aSameDayWindowAndAZeroLengthWindow() {
        UserAlertPreference nap = new UserAlertPreference();
        nap.setQuietStart(LocalTime.of(13, 0));
        nap.setQuietEnd(LocalTime.of(15, 0));
        nap.setTimezone("UTC");
        QuietHoursCatchUpService.Window w = QuietHoursCatchUpService.lastEndedWindow(
                nap, Instant.parse("2026-10-07T16:00:00Z"));
        assertThat(w.start()).isEqualTo(Instant.parse("2026-10-07T13:00:00Z"));
        assertThat(w.end()).isEqualTo(Instant.parse("2026-10-07T15:00:00Z"));

        nap.setQuietEnd(LocalTime.of(13, 0));
        assertThat(QuietHoursCatchUpService.lastEndedWindow(nap, MORNING)).isNull();
    }

    // ── who gets a summary ───────────────────────────────────────────────

    @Test
    void oneSummaryAfterTheWindowWhenSomethingWasHeld() {
        sleeper("ana@x.com");
        heldOvernight("ana@x.com", 3);

        assertThat(service(MORNING).handle("ana@x.com", MORNING)).isTrue();

        verify(notifications).sendQuietHoursCatchUp("ana@x.com", "tok-ana@x.com",
                "Your inbox", "3 updates while your notifications were quiet.", 3);
    }

    @Test
    void theCopyIsSingularForOne() {
        assertThat(QuietHoursCatchUpService.body(1)).isEqualTo("1 update while your notifications were quiet.");
    }

    @Test
    void nothingWhenEverythingHeldWasAlreadyRead() {
        sleeper("ana@x.com");
        heldOvernight("ana@x.com", 0);   // the query counts unread rows only

        assertThat(service(MORNING).handle("ana@x.com", MORNING)).isFalse();
        verify(notifications, never()).sendQuietHoursCatchUp(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void nothingOnceTheGracePeriodHasPassed() {
        sleeper("ana@x.com");
        heldOvernight("ana@x.com", 2);
        Instant afternoon = WINDOW_END.plus(QuietHoursCatchUpService.GRACE).plusSeconds(60);

        assertThat(service(afternoon).handle("ana@x.com", afternoon)).isFalse();
        verify(notifications, never()).sendQuietHoursCatchUp(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void nothingWhenPushIsOffOrQuietHoursWereTurnedOff() {
        sleeper("ana@x.com").setPushEnabled(false);
        heldOvernight("ana@x.com", 2);
        assertThat(service(MORNING).handle("ana@x.com", MORNING)).isFalse();

        sleeper("bo@x.com").setQuietHoursEnabled(false);
        heldOvernight("bo@x.com", 2);
        assertThat(service(MORNING).handle("bo@x.com", MORNING)).isFalse();

        verify(notifications, never()).sendQuietHoursCatchUp(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void onceperNight_theSummaryRowIsTheStamp() {
        sleeper("ana@x.com");
        heldOvernight("ana@x.com", 2);
        when(logRepo.existsTypeSince("ana@x.com", NotificationService.TYPE_QUIET_HOURS_CATCH_UP, WINDOW_END))
                .thenReturn(false, true);
        QuietHoursCatchUpService svc = service(MORNING);

        assertThat(svc.handle("ana@x.com", MORNING)).isTrue();
        assertThat(svc.handle("ana@x.com", MORNING.plusSeconds(900))).isFalse();

        verify(notifications, times(1)).sendQuietHoursCatchUp(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void nothingWithoutAPushToken() {
        sleeper("ana@x.com");
        heldOvernight("ana@x.com", 2);
        when(users.findByUserEmailIgnoreCase("ana@x.com")).thenReturn(Optional.of(new UserInfo()));

        assertThat(service(MORNING).handle("ana@x.com", MORNING)).isFalse();
    }

    // ── the send itself (real NotificationService) ───────────────────────

    @Test
    void theSummaryWritesAReadArchivedAuditRowAndNoInboxEvent() {
        io.sitprep.sitprepapi.websocket.WebSocketMessageSender ws =
                mock(io.sitprep.sitprepapi.websocket.WebSocketMessageSender.class);
        PushPolicyService policy = mock(PushPolicyService.class);
        when(policy.evaluate("ana@x.com", PushPolicyService.Category.QUIET_HOURS_CATCH_UP, null))
                .thenReturn(PushPolicyService.Lane.A);
        when(logRepo.save(any(io.sitprep.sitprepapi.domain.NotificationLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        NotificationService real = new NotificationService(ws, users, logRepo,
                mock(io.sitprep.sitprepapi.websocket.WebSocketPresenceService.class), policy,
                mock(GroupMuteService.class));

        real.sendQuietHoursCatchUp("ana@x.com", "tok", "Your inbox", QuietHoursCatchUpService.body(2), 2);

        org.mockito.ArgumentCaptor<io.sitprep.sitprepapi.domain.NotificationLog> row =
                org.mockito.ArgumentCaptor.forClass(io.sitprep.sitprepapi.domain.NotificationLog.class);
        verify(logRepo).save(row.capture());
        assertThat(row.getValue().getType()).isEqualTo(NotificationService.TYPE_QUIET_HOURS_CATCH_UP);
        assertThat(row.getValue().getTargetUrl()).isEqualTo("/notifications");
        assertThat(row.getValue().getReadAt()).as("never unread").isNotNull();
        assertThat(row.getValue().getArchivedAt()).as("never in the inbox list").isNotNull();
        verify(ws, never()).sendInboxEvent(anyString(), any());
        verify(ws, never()).sendInAppNotification(any());
    }

    @Test
    void theSummarySendsNothingUnlessPolicySaysLaneA() {
        PushPolicyService policy = mock(PushPolicyService.class);
        when(policy.evaluate(anyString(), any(), any())).thenReturn(PushPolicyService.Lane.B);
        NotificationService real = new NotificationService(
                mock(io.sitprep.sitprepapi.websocket.WebSocketMessageSender.class), users, logRepo,
                mock(io.sitprep.sitprepapi.websocket.WebSocketPresenceService.class), policy,
                mock(GroupMuteService.class));

        assertThat(real.sendQuietHoursCatchUp("ana@x.com", "tok", "Your inbox", "x", 1)).isFalse();
        verify(logRepo, never()).save(any());
    }

    // ── paging ───────────────────────────────────────────────────────────

    @Test
    void theSweepKeysetPagesPastAFullPage() {
        int total = QuietHoursCatchUpService.BATCH_SIZE + 5;
        List<String> emails = new ArrayList<>(IntStream.range(0, total)
                .mapToObj(i -> String.format("u%04d@x.com", i)).toList());
        for (String e : emails) {
            sleeper(e);
            heldOvernight(e, 1);
        }
        when(logRepo.findDeferredUnreadRecipients(eq("QUIET_HOURS"), any(), any(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    String after = inv.getArgument(2);
                    Pageable page = inv.getArgument(3);
                    return emails.stream().filter(e -> e.compareTo(after) > 0)
                            .limit(page.getPageSize()).toList();
                });

        assertThat(service(MORNING).sweepOnce()).isEqualTo(total);

        verify(logRepo).findDeferredUnreadRecipients(eq("QUIET_HOURS"),
                eq(MORNING.minus(QuietHoursCatchUpService.LOOKBACK)), eq(""), any(Pageable.class));
        verify(logRepo).findDeferredUnreadRecipients(eq("QUIET_HOURS"), any(),
                eq(emails.get(QuietHoursCatchUpService.BATCH_SIZE - 1)), any(Pageable.class));
    }

    // ── scheduling switch ────────────────────────────────────────────────

    @Test
    void theJobIsOffWhenSchedulingIsDisabled() throws Exception {
        // The sweep is a plain @Scheduled method; only SchedulingConfig turns
        // scheduling on, and it is absent when the flag is false.
        assertThat(QuietHoursCatchUpService.class.getMethod("scheduledSweep")
                .isAnnotationPresent(Scheduled.class)).isTrue();
        new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class)
                .withPropertyValues("app.scheduling.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SchedulingConfig.class)
                        .doesNotHaveBean(ThreadPoolTaskScheduler.class));
        new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(SchedulingConfig.class));
    }
}
