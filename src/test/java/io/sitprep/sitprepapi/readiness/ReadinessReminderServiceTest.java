package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.config.SchedulingConfig;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.AreaDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.CapabilitiesDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.EssentialsDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ItemDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ReadinessJourneyDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.NotificationService;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Remind me later" sends one real reminder (EXEC-A1 task 1). The due-row
 * query and the conditional claim are proven on H2 in
 * {@link HouseholdReadinessItemStateRepoTest}; this covers what the sweep does
 * with each due row.
 */
class ReadinessReminderServiceTest {

    static final String HH = "hh-1";
    static final String ME = "member@example.com";
    static final String KEY = "outage.charge_plan";
    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    final HouseholdReadinessItemStateRepo stateRepo = mock(HouseholdReadinessItemStateRepo.class);
    final ReadinessJourneyService journeyService = mock(ReadinessJourneyService.class);
    final EssentialsReadinessService essentials = mock(EssentialsReadinessService.class);
    final UserInfoRepo users = mock(UserInfoRepo.class);
    final NotificationService notifications = mock(NotificationService.class);

    final ReadinessReminderService service = new ReadinessReminderService(
            stateRepo, journeyService, essentials, users, notifications, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(essentials.isRequesterBase(HH, ME)).thenReturn(true);
        UserInfo u = new UserInfo();
        u.setUserEmail(ME);
        u.setFcmtoken("fcm-token");
        when(users.findByUserEmailIgnoreCase(ME)).thenReturn(Optional.of(u));
        when(stateRepo.claimReminder(anyLong(), eq(ItemStateKind.REMIND_LATER), any(), eq(NOW))).thenReturn(1);
    }

    // ------------------------------------------------------------------ fixtures

    static HouseholdReadinessItemState due(long id, String key) {
        HouseholdReadinessItemState r = new HouseholdReadinessItemState();
        r.setId(id);
        r.setHouseholdId(HH);
        r.setItemKey(key);
        r.setScope(HouseholdReadinessItemState.SCOPE_USER);
        r.setUserEmail(ME);
        r.setState(ItemStateKind.REMIND_LATER);
        r.setRemindAt(NOW.minus(Duration.ofMinutes(5)));
        return r;
    }

    static ItemDto item(String key, CompletionState completion, Freshness freshness, ItemStateKind householdState) {
        return new ItemDto(key, ReadinessArea.OUTAGE, ReadinessScope.HOUSEHOLD, "Have a way to charge phones",
                "d", List.of(), completion, CompletionSource.MANUAL, null, freshness, null, householdState, null,
                null, null, null, List.of(), null,
                new CapabilitiesDto(false, false, false, false, false, false));
    }

    static ReadinessJourneyDto journey(JourneyMode mode, ItemDto... items) {
        return new ReadinessJourneyDto(1, "c", "r", HH, NOW, mode, new EssentialsDto(true, 4, 4, null), null, null, 0,
                false, null,
                List.of(new AreaDto(ReadinessArea.OUTAGE, "Outage Ready", "d", 0, items.length, null, null, List.of(items))),
                List.of());
    }

    void rows(HouseholdReadinessItemState... rows) {
        when(stateRepo.findDueReminders(eq(ItemStateKind.REMIND_LATER), eq(NOW), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(rows));
    }

    void journeyIs(ReadinessJourneyDto j) {
        when(journeyService.journeyForReminder(HH, ME, NOW)).thenReturn(Optional.of(j));
    }

    void assertClosedWithoutSending(long id) {
        verify(stateRepo).claimReminder(eq(id), eq(ItemStateKind.REMIND_LATER), any(), eq(NOW));
        verify(notifications, never()).deliverPresenceAware(any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(Category.class));
    }

    // ------------------------------------------------------------------ send

    @Test
    void aDueStepSendsOneCalmReminder_claimedBeforeItIsSent() {
        rows(due(7, KEY));
        journeyIs(journey(JourneyMode.CALM, item(KEY, CompletionState.INCOMPLETE, null, null)));

        assertThat(service.sweepOnce()).isEqualTo(1);

        InOrder order = inOrder(stateRepo, notifications);
        order.verify(stateRepo).claimReminder(7L, ItemStateKind.REMIND_LATER, NOW.minus(Duration.ofMinutes(5)), NOW);
        order.verify(notifications).deliverPresenceAware(
                eq(ME), eq("A step you saved for later"), eq("Have a way to charge phones"), eq("SitPrep"),
                eq("/images/icon-120.png"), eq("readiness_reminder"), eq(KEY), eq("/ready-for-more"),
                contains("\"householdId\":\"hh-1\""), eq("fcm-token"), eq(Category.READINESS_REMINDER));
    }

    @Test
    void aCompletedStepThatIsDueForReviewStillSends() {
        rows(due(7, KEY));
        journeyIs(journey(JourneyMode.CALM, item(KEY, CompletionState.COMPLETE, Freshness.REVIEW_DUE, ItemStateKind.DONE)));
        assertThat(service.sweepOnce()).isEqualTo(1);
    }

    @Test
    void aLostClaimSendsNothing() {
        rows(due(7, KEY));
        journeyIs(journey(JourneyMode.CALM, item(KEY, CompletionState.INCOMPLETE, null, null)));
        when(stateRepo.claimReminder(anyLong(), any(), any(), any())).thenReturn(0);

        assertThat(service.sweepOnce()).isZero();
        verify(notifications, never()).deliverPresenceAware(any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(Category.class));
    }

    // ------------------------------------------------------------------ defer

    @Test
    void anActiveResponseDefersTheReminder_unstampedSoALaterSweepRetries() {
        rows(due(7, KEY));
        journeyIs(journey(JourneyMode.ACTIVE_RESPONSE, item(KEY, CompletionState.INCOMPLETE, null, null)));

        assertThat(service.sweepOnce()).isZero();
        verify(stateRepo, never()).claimReminder(anyLong(), any(), any(), any());
        verify(notifications, never()).deliverPresenceAware(any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(Category.class));
    }

    // ------------------------------------------------------------------ close

    @Test
    void aStepThatIsNowDoneIsClosedWithoutSending() {
        rows(due(7, KEY));
        journeyIs(journey(JourneyMode.CALM, item(KEY, CompletionState.COMPLETE, Freshness.CURRENT, ItemStateKind.DONE)));
        assertThat(service.sweepOnce()).isZero();
        assertClosedWithoutSending(7);
    }

    @Test
    void aStepMarkedNotRelevantIsClosedWithoutSending() {
        rows(due(7, KEY));
        journeyIs(journey(JourneyMode.CALM, item(KEY, CompletionState.INCOMPLETE, null, ItemStateKind.NOT_RELEVANT)));
        assertThat(service.sweepOnce()).isZero();
        assertClosedWithoutSending(7);
    }

    @Test
    void aStepNoLongerInTheJourneyIsClosed() {
        // e.g. a local-risk step the household's risk profile no longer adds.
        rows(due(7, "local_risk.flood_sandbags"));
        journeyIs(journey(JourneyMode.CALM, item(KEY, CompletionState.INCOMPLETE, null, null)));
        assertThat(service.sweepOnce()).isZero();
        assertClosedWithoutSending(7);
    }

    @Test
    void aMemberWhoLeftTheHouseholdIsNotReminded() {
        rows(due(7, KEY));
        when(journeyService.journeyForReminder(HH, ME, NOW)).thenReturn(Optional.empty());
        assertThat(service.sweepOnce()).isZero();
        assertClosedWithoutSending(7);
    }

    @Test
    void aHouseholdThatIsNoLongerTheMembersBaseIsNotReminded() {
        // /ready-for-more opens the base household's journey, so the tap would land elsewhere.
        rows(due(7, KEY));
        when(essentials.isRequesterBase(HH, ME)).thenReturn(false);
        assertThat(service.sweepOnce()).isZero();
        verify(journeyService, never()).journeyForReminder(any(), any(), any());
        assertClosedWithoutSending(7);
    }

    @Test
    void aDeletedAccountIsClosedWithoutSending() {
        rows(due(7, KEY));
        journeyIs(journey(JourneyMode.CALM, item(KEY, CompletionState.INCOMPLETE, null, null)));
        when(users.findByUserEmailIgnoreCase(ME)).thenReturn(Optional.empty());
        assertThat(service.sweepOnce()).isZero();
        assertClosedWithoutSending(7);
    }

    // ------------------------------------------------------------------ paging + cost

    @Test
    void pagesByKeysetPastAFullBatchOfDeferredRows() {
        // A full first page, all deferred by an active response elsewhere...
        List<HouseholdReadinessItemState> first = new ArrayList<>();
        for (long id = 1; id <= ReadinessReminderService.BATCH_SIZE; id++) {
            HouseholdReadinessItemState r = due(id, KEY);
            r.setHouseholdId("hh-busy");
            r.setUserEmail("busy@example.com");
            first.add(r);
        }
        when(stateRepo.findDueReminders(eq(ItemStateKind.REMIND_LATER), eq(NOW), eq(0L), any(Pageable.class)))
                .thenReturn(first);
        when(essentials.isRequesterBase("hh-busy", "busy@example.com")).thenReturn(true);
        when(journeyService.journeyForReminder("hh-busy", "busy@example.com", NOW))
                .thenReturn(Optional.of(journey(JourneyMode.ACTIVE_RESPONSE, item(KEY, CompletionState.INCOMPLETE, null, null))));
        // ...must not starve the row behind them.
        long last = ReadinessReminderService.BATCH_SIZE;
        when(stateRepo.findDueReminders(eq(ItemStateKind.REMIND_LATER), eq(NOW), eq(last), any(Pageable.class)))
                .thenReturn(List.of(due(last + 1, KEY)));
        journeyIs(journey(JourneyMode.CALM, item(KEY, CompletionState.INCOMPLETE, null, null)));

        assertThat(service.sweepOnce()).isEqualTo(1);
        verify(stateRepo).claimReminder(eq(last + 1), eq(ItemStateKind.REMIND_LATER), any(), eq(NOW));
        // One journey per household + member per sweep, not one per row.
        verify(journeyService, times(1)).journeyForReminder("hh-busy", "busy@example.com", NOW);
    }

    @Test
    void decisionMatrix() {
        var calm = Optional.of(journey(JourneyMode.CALM));
        assertThat(ReadinessReminderService.decide(Optional.empty(), Optional.empty()))
                .isEqualTo(ReadinessReminderService.Decision.CLOSE);
        assertThat(ReadinessReminderService.decide(Optional.of(journey(JourneyMode.ACTIVE_RESPONSE)), Optional.empty()))
                .isEqualTo(ReadinessReminderService.Decision.DEFER);
        assertThat(ReadinessReminderService.decide(calm, Optional.of(item(KEY, CompletionState.COMPLETE, Freshness.REVIEW_SOON, null))))
                .isEqualTo(ReadinessReminderService.Decision.CLOSE);
        assertThat(ReadinessReminderService.decide(calm, Optional.of(item(KEY, CompletionState.COMPLETE, null, null))))
                .isEqualTo(ReadinessReminderService.Decision.CLOSE);
        assertThat(ReadinessReminderService.decide(Optional.of(journey(JourneyMode.ESSENTIALS_FIRST)),
                Optional.of(item(KEY, CompletionState.INCOMPLETE, null, null))))
                .isEqualTo(ReadinessReminderService.Decision.SEND);
    }

    // ------------------------------------------------------------------ lane

    @Test
    void theReminderPushesButQuietHoursAndPushOffDeferItToTheInbox() {
        var prefs = mock(io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo.class);
        var limiter = mock(io.sitprep.sitprepapi.service.RateLimiterService.class);
        var row = new io.sitprep.sitprepapi.domain.UserAlertPreference();
        row.setUserEmail(ME);
        when(prefs.findByEmail(ME)).thenReturn(Optional.of(row));
        when(limiter.tryConsume(any(), any())).thenReturn(true);
        var policy = new io.sitprep.sitprepapi.service.PushPolicyService(prefs, limiter);
        var A = io.sitprep.sitprepapi.service.PushPolicyService.Lane.A;
        var B = io.sitprep.sitprepapi.service.PushPolicyService.Lane.B;

        assertThat(policy.evaluate(ME, Category.READINESS_REMINDER, null)).isEqualTo(A);

        // Quiet window centred on now: not critical, so it waits in the inbox.
        java.time.LocalTime t = java.time.LocalTime.now(ZoneOffset.UTC);
        row.setQuietHoursEnabled(true);
        row.setTimezone("UTC");
        row.setQuietStart(t.minusHours(2));
        row.setQuietEnd(t.plusHours(2));
        assertThat(policy.evaluate(ME, Category.READINESS_REMINDER, null)).isEqualTo(B);

        row.setQuietHoursEnabled(false);
        row.setPushEnabled(false);
        assertThat(policy.evaluate(ME, Category.READINESS_REMINDER, null)).isEqualTo(B);

        // Over the rate cap: inbox, never dropped.
        row.setPushEnabled(true);
        when(limiter.tryConsume(any(), any())).thenReturn(false);
        assertThat(policy.evaluate(ME, Category.READINESS_REMINDER, null)).isEqualTo(B);
    }

    // ------------------------------------------------------------------ scheduling flag

    @Test
    void theSweepIsAScheduledJobAndTheSchedulingFlagTurnsItOff() throws Exception {
        assertThat(ReadinessReminderService.class.getMethod("scheduledSweep").getAnnotation(Scheduled.class))
                .isNotNull()
                .satisfies(s -> assertThat(s.fixedDelayString()).isEqualTo("PT15M"));
        // @EnableScheduling lives only on SchedulingConfig, which the flag removes.
        new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class)
                .withPropertyValues("app.scheduling.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SchedulingConfig.class)
                        .doesNotHaveBean(ThreadPoolTaskScheduler.class));
        new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    void noRowsNoWork() {
        when(stateRepo.findDueReminders(any(), any(), anyLong(), any(Pageable.class))).thenReturn(List.of());
        assertThat(service.sweepOnce()).isZero();
        verify(journeyService, never()).journeyForReminder(any(), any(), any());
    }
}
