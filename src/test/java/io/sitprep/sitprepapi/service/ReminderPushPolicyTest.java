package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.GoBag;
import io.sitprep.sitprepapi.domain.GoBagItem;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.notifications.NotificationEventType;
import io.sitprep.sitprepapi.repo.GoBagItemRepo;
import io.sitprep.sitprepapi.repo.GoBagRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.service.PushPolicyService.Lane;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The task / go-bag / guest-expiry reminders go through push policy (EXEC-N,
 * 2026-10-07). They were no-policy types: a full push at whatever hour the 24h
 * sweep ran, ignoring quiet hours, rate caps and the master push switch.
 *
 * <p>Real {@link PushPolicyService} over mocked preferences; a real
 * {@link NotificationService} for the row the policy produces.</p>
 */
class ReminderPushPolicyTest {

    private final Map<String, UserAlertPreference> prefs = new HashMap<>();
    private RateLimiterService rateLimiter;
    private PushPolicyService policy;
    private NotificationLogRepo logRepo;
    private NotificationService notifications;

    @BeforeEach
    void setUp() {
        UserAlertPreferenceRepo prefRepo = mock(UserAlertPreferenceRepo.class);
        when(prefRepo.findByEmail(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(prefs.get((String) inv.getArgument(0))));
        when(prefRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        rateLimiter = mock(RateLimiterService.class);
        when(rateLimiter.tryConsume(any(), any())).thenReturn(true);
        policy = new PushPolicyService(prefRepo, rateLimiter);

        logRepo = mock(NotificationLogRepo.class);
        when(logRepo.save(any(NotificationLog.class))).thenAnswer(inv -> inv.getArgument(0));
        notifications = new NotificationService(mock(WebSocketMessageSender.class), mock(UserInfoRepo.class),
                logRepo, mock(WebSocketPresenceService.class), policy, mock(GroupMuteService.class));
    }

    private void asleep(String email) {
        UserAlertPreference p = new UserAlertPreference();
        p.setUserEmail(email);
        LocalTime now = LocalTime.now(ZoneOffset.UTC);
        p.setQuietHoursEnabled(true);
        p.setTimezone("UTC");
        p.setQuietStart(now.minusHours(2));
        p.setQuietEnd(now.plusHours(2));
        prefs.put(email, p);
    }

    private NotificationLog sendTaskReminder(String email) {
        notifications.deliverPresenceAware(email, "Time to refresh your prep", "Water is due.",
                "SitPrep", "/images/icon-120.png", "task_reminder", "42", "/me/tasks", null, "tok",
                Category.READINESS_REMINDER);
        ArgumentCaptor<NotificationLog> row = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepo).save(row.capture());
        return row.getValue();
    }

    // ── taxonomy ─────────────────────────────────────────────────────────

    @Test
    void everyReminderTypeNowHasANonCriticalLaneACategory() {
        assertThat(NotificationService.mapTypeToCategory("task_reminder")).isEqualTo(Category.READINESS_REMINDER);
        assertThat(NotificationService.mapTypeToCategory("gobag_expiry")).isEqualTo(Category.READINESS_REMINDER);
        assertThat(NotificationService.mapTypeToCategory("guest_expiry_reminder")).isEqualTo(Category.ACCOUNT_REMINDER);
        assertThat(PushPolicyService.defaultLaneFor(Category.READINESS_REMINDER)).isEqualTo(Lane.A);
        assertThat(PushPolicyService.defaultLaneFor(Category.ACCOUNT_REMINDER)).isEqualTo(Lane.A);
        // The inbox still renders them by TYPE: the category does not change the card.
        assertThat(NotificationEventType.resolve("task_reminder", "READINESS_REMINDER", "42"))
                .isEqualTo(NotificationEventType.TASK_REMINDER);
        assertThat(NotificationEventType.resolve("gobag_expiry", "READINESS_REMINDER", "hh1"))
                .isEqualTo(NotificationEventType.GO_BAG_EXPIRY);
    }

    // ── lanes, through the real send path ────────────────────────────────

    @Test
    void quietHoursDeferAReminderToAMarkedInboxRow() {
        asleep("ana@x.com");

        NotificationLog row = sendTaskReminder("ana@x.com");

        assertThat(row.getLane()).isEqualTo("B");
        assertThat(row.getErrorMessage()).isEqualTo(NotificationService.LANE_B_SILENT_INBOX);
        assertThat(row.getDeferredReason()).isEqualTo("QUIET_HOURS");
        assertThat(row.getCategory()).isEqualTo("READINESS_REMINDER");
    }

    @Test
    void theRateCapAppliesAndIsNotMarkedAsQuietHours() {
        when(rateLimiter.tryConsume(eq("ana@x.com"), eq(Category.READINESS_REMINDER))).thenReturn(false);

        NotificationLog row = sendTaskReminder("ana@x.com");

        assertThat(row.getLane()).isEqualTo("B");
        assertThat(row.getDeferredReason()).isNull();
    }

    @Test
    void outsideQuietHoursAReminderIsStillAPush() {
        NotificationLog row = sendTaskReminder("ana@x.com");

        // Lane A: the FCM send was attempted (no Firebase app in a unit test,
        // so it fails), and the row is the push's audit row, not an inbox defer.
        assertThat(row.getLane()).isEqualTo("A");
        assertThat(row.getDeferredReason()).isNull();
    }

    @Test
    void guestExpiryIsDeferredByQuietHoursToo() {
        asleep("guest@x.com");
        assertThat(policy.decide("guest@x.com", Category.ACCOUNT_REMINDER, null).deferredByQuietHours()).isTrue();
    }

    // ── agency tiers (the explicit bypass) ───────────────────────────────

    @Test
    void onlyTheEmergencyAgencyTierBypassesQuietHours() {
        asleep("a@x.com");
        assertThat(policy.evaluate("a@x.com", Category.AGENCY_ALERT, "emergency")).isEqualTo(Lane.A);
        assertThat(policy.evaluate("a@x.com", Category.AGENCY_ALERT, " EMERGENCY ")).isEqualTo(Lane.A);
        for (String tier : new String[] {"advisory", "notice", null}) {
            assertThat(policy.decide("a@x.com", Category.AGENCY_ALERT, tier).deferredByQuietHours())
                    .as(String.valueOf(tier)).isTrue();
        }
    }

    // ── the senders pass the category ────────────────────────────────────

    @Test
    void theTaskReminderSweepSendsWithTheReminderCategory() {
        PostRepo postRepo = mock(PostRepo.class);
        UserInfoRepo users = mock(UserInfoRepo.class);
        NotificationService ns = mock(NotificationService.class);
        Post task = new Post();
        task.setId(42L);
        task.setRequesterEmail("ana@x.com");
        task.setTitle("Water");
        when(postRepo.findPersonalTasksDueForReminder(any(), any(), any())).thenReturn(List.of(task));
        when(users.findByUserEmailIgnoreCase("ana@x.com")).thenReturn(Optional.empty());

        assertThat(new PersonalTaskReminderService(postRepo, users, ns).sweepOnce()).isEqualTo(1);

        verify(ns).deliverPresenceAware(eq("ana@x.com"), anyString(), anyString(), anyString(), anyString(),
                eq("task_reminder"), eq("42"), eq("/me/tasks"), any(), any(), eq(Category.READINESS_REMINDER));
    }

    @Test
    void theGoBagSweepSendsWithTheReminderCategory() {
        GoBagItemRepo items = mock(GoBagItemRepo.class);
        GoBagRepo bags = mock(GoBagRepo.class);
        GroupRepo groups = mock(GroupRepo.class);
        UserInfoRepo users = mock(UserInfoRepo.class);
        NotificationService ns = mock(NotificationService.class);
        GoBagItem item = new GoBagItem();
        item.setBagId("bag1");
        item.setLabel("Water pouches");
        item.setExpiresOn(LocalDate.now().plusDays(3));
        GoBag bag = new GoBag();
        bag.setId("bag1");
        bag.setHouseholdId("hh1");
        Group hh = new Group();
        hh.setGroupId("hh1");
        hh.setOwnerEmail("ana@x.com");
        when(items.findDueForExpiryReminder(any(), any())).thenReturn(List.of(item));
        when(bags.findById("bag1")).thenReturn(Optional.of(bag));
        when(groups.findByGroupId("hh1")).thenReturn(Optional.of(hh));
        when(users.findByUserEmailIgnoreCase("ana@x.com")).thenReturn(Optional.empty());

        new GoBagExpiryReminderService(items, bags, groups, users, ns).sweepOnce();

        verify(ns).deliverPresenceAware(eq("ana@x.com"), anyString(), anyString(), anyString(), anyString(),
                eq("gobag_expiry"), eq("hh1"), eq("/go-bag"), any(), any(), eq(Category.READINESS_REMINDER));
    }

    @Test
    void theGuestExpirySweepSendsWithTheAccountCategory() {
        UserInfoRepo users = mock(UserInfoRepo.class);
        NotificationService ns = mock(NotificationService.class);
        UserInfo guest = new UserInfo();
        guest.setUserEmail("guest@x.com");
        guest.setFirebaseUid("uid-1");
        guest.setFcmtoken("tok");
        when(users.findGuestAccountsNeedingExpiryReminder(any(Instant.class), any(Instant.class), any()))
                .thenReturn(List.of(guest));

        new GuestExpiryReminderService(users, ns).sweepOnce();

        verify(ns).deliverPresenceAware(eq("guest@x.com"), anyString(), anyString(), anyString(), anyString(),
                eq("guest_expiry_reminder"), eq("uid-1"), eq("/login"), anyString(), eq("tok"),
                eq(Category.ACCOUNT_REMINDER));
    }
}
