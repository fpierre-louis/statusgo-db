package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AgencyAlert;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.AgencyAlertRepo;
import io.sitprep.sitprepapi.repo.AgencyAlertDispatchAttemptRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgencyAlertDispatchServiceTest {

    @Mock AgencyAlertRepo alertRepo;
    @Mock AgencyAlertDispatchAttemptRepo attemptRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock NotificationService notifications;
    @Mock TransactionTemplate transactions;
    @Mock TransactionStatus transactionStatus;
    @Mock UserAlertPreferenceRepo prefRepo;
    @Mock RateLimiterService rateLimiter;
    @Mock NotificationLogRepo logRepo;

    AgencyAlertDispatchService service;
    /** Real policy over mocked preferences: the lane decision is under test. */
    final Map<String, UserAlertPreference> prefs = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(transactionStatus));
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(transactions).executeWithoutResult(any());
        lenient().when(prefRepo.findByEmail(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(prefs.get((String) inv.getArgument(0))));
        lenient().when(prefRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(rateLimiter.tryConsume(any(), any())).thenReturn(true);
        PushPolicyService policy = new PushPolicyService(prefRepo, rateLimiter);
        service = new AgencyAlertDispatchService(alertRepo, attemptRepo, userInfoRepo, notifications,
                transactions, policy, logRepo);
    }

    @Test
    void zeroRecipientAlertCompletesAsSent() {
        AgencyAlert alert = alert(List.of(), 1);
        prepareClaim(alert);

        service.dispatchNow(alert.getId());

        verify(notifications, never()).sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any());
        verifyNoInteractions(logRepo);   // a first attempt has no earlier rows to look up

        assertThat(alert.getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.SENT);
        assertThat(alert.getDeliveredCount()).isZero();
        assertThat(alert.getCompletedAt()).isNotNull();
        verify(attemptRepo).save(argThat(attempt ->
                attempt.getStatus() == AgencyAlert.DispatchStatus.SENT
                        && attempt.getAttemptNumber() == 1));
    }

    @Test
    void partialProviderResultIsOperatorVisibleAndNotAutomaticallyRetried() {
        AgencyAlert alert = alert(List.of("a@example.com", "b@example.com"), 1);
        prepareClaim(alert);
        when(userInfoRepo.findByUserEmailLowerIn(alert.getRecipientEmails()))
                .thenReturn(List.of(user("b@example.com"), user("a@example.com")));
        when(notifications.sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(new NotificationService.HazardBatchResult(2, 1, 1, "token rejected"));

        service.dispatchNow(alert.getId());

        assertThat(alert.getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.PARTIAL);
        assertThat(alert.getAttemptedCount()).isEqualTo(2);
        assertThat(alert.getDeliveredCount()).isEqualTo(1);
        assertThat(alert.getFailedCount()).isEqualTo(1);
        assertThat(alert.getNextAttemptAt()).isNull();
        verify(attemptRepo).save(argThat(attempt ->
                attempt.getStatus() == AgencyAlert.DispatchStatus.PARTIAL
                        && attempt.getDeliveredCount() == 1
                        && attempt.getFailedCount() == 1));
    }

    @Test
    void zeroDeliveryFailureSchedulesBoundedRetry() {
        AgencyAlert alert = alert(List.of("a@example.com"), 2);
        prepareClaim(alert);
        when(userInfoRepo.findByUserEmailLowerIn(alert.getRecipientEmails()))
                .thenReturn(List.of(user("a@example.com")));
        when(notifications.sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(new NotificationService.HazardBatchResult(1, 0, 1, "provider unavailable"));

        service.dispatchNow(alert.getId());

        assertThat(alert.getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.FAILED);
        assertThat(alert.getNextAttemptAt()).isNotNull();
        assertThat(alert.getCompletedAt()).isNull();
        verify(attemptRepo).save(argThat(attempt ->
                attempt.getStatus() == AgencyAlert.DispatchStatus.FAILED
                        && attempt.getAttemptNumber() == 2));
    }

    // ── Push policy (EXEC-N): the bypass is the agency's tier, made explicit ──

    @Test
    void anEmergencyAlertStillPushesInsideQuietHours() {
        AgencyAlert alert = alert(List.of("a@example.com"), 1);
        alert.setOfficialTier("emergency");
        prepareClaim(alert);
        quietNow("a@example.com");
        when(userInfoRepo.findByUserEmailLowerIn(alert.getRecipientEmails()))
                .thenReturn(List.of(user("a@example.com")));
        when(notifications.sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(new NotificationService.HazardBatchResult(1, 1, 0, null));

        service.dispatchNow(alert.getId());

        verify(notifications).sendHazardAlertBatch(argThat(l -> l.size() == 1), eq("Water advisory"),
                anyString(), eq("agency-alert:17"), eq("/community/posts/17"), any(), anyBoolean(), any(), any(), any());
        verify(notifications, never()).logHazardAlertInboxOnly(anyList(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any());
        assertThat(alert.getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.SENT);
        assertThat(alert.getDeliveredCount()).isEqualTo(1);
    }

    @Test
    void anAdvisoryWaitsForMorning_asAMarkedInboxRow() {
        AgencyAlert alert = alert(List.of("a@example.com", "b@example.com"), 1);
        alert.setOfficialTier("advisory");
        prepareClaim(alert);
        quietNow("a@example.com");                       // a: asleep; b: no quiet hours
        when(userInfoRepo.findByUserEmailLowerIn(alert.getRecipientEmails()))
                .thenReturn(List.of(user("a@example.com"), user("b@example.com")));
        when(notifications.sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(new NotificationService.HazardBatchResult(1, 1, 0, null));
        when(notifications.logHazardAlertInboxOnly(anyList(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any())).thenReturn(1);

        service.dispatchNow(alert.getId());

        verify(notifications).sendHazardAlertBatch(
                argThat(l -> l.size() == 1 && "b@example.com".equals(l.get(0).getUserEmail())),
                anyString(), anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any());
        verify(notifications).logHazardAlertInboxOnly(
                argThat(l -> l.size() == 1 && "a@example.com".equals(l.get(0).getUserEmail())),
                eq("Water advisory"), anyString(), eq("agency-alert:17"), eq("/community/posts/17"),
                isNull(), eq(Category.AGENCY_ALERT), eq(Set.of("a@example.com")), any());
        // Both reached the recipient: one push, one inbox row. Not a failure, not retried.
        assertThat(alert.getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.SENT);
        assertThat(alert.getDeliveredCount()).isEqualTo(2);
        assertThat(alert.getFailedCount()).isZero();
    }

    @Test
    void anyTierHonoursTheMasterPushSwitch_withAnUnmarkedInboxRow() {
        AgencyAlert alert = alert(List.of("a@example.com"), 1);
        alert.setOfficialTier("emergency");
        prepareClaim(alert);
        UserAlertPreference p = new UserAlertPreference();
        p.setUserEmail("a@example.com");
        p.setPushEnabled(false);
        prefs.put("a@example.com", p);
        when(userInfoRepo.findByUserEmailLowerIn(alert.getRecipientEmails()))
                .thenReturn(List.of(user("a@example.com")));
        when(notifications.logHazardAlertInboxOnly(anyList(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any())).thenReturn(1);

        service.dispatchNow(alert.getId());

        verify(notifications, never()).sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any());
        verify(notifications).logHazardAlertInboxOnly(anyList(), anyString(), anyString(), anyString(),
                anyString(), isNull(), eq(Category.AGENCY_ALERT), eq(Set.of()), any());
        assertThat(alert.getDeliveredCount()).isEqualTo(1);
    }

    // ── D10: a retry never writes a second row; "no token" is not retried ──

    @Test
    void allNoTokenFailure_isFinal_notRetried() {
        AgencyAlert alert = alert(List.of("a@example.com", "b@example.com"), 1);
        prepareClaim(alert);
        when(userInfoRepo.findByUserEmailLowerIn(alert.getRecipientEmails()))
                .thenReturn(List.of(user("a@example.com"), user("b@example.com")));
        when(notifications.sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(new NotificationService.HazardBatchResult(2, 0, 2, "No token", 2));

        service.dispatchNow(alert.getId());

        // Nothing a retry could change: both already hold their inbox row.
        assertThat(alert.getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.FAILED);
        assertThat(alert.getNextAttemptAt()).isNull();
        assertThat(alert.getCompletedAt()).isNotNull();
    }

    @Test
    void aMissingAccountAloneIsFinal_notRetried() {
        AgencyAlert alert = alert(List.of("gone@example.com"), 1);
        prepareClaim(alert);
        when(userInfoRepo.findByUserEmailLowerIn(alert.getRecipientEmails())).thenReturn(List.of());

        service.dispatchNow(alert.getId());

        assertThat(alert.getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.FAILED);
        assertThat(alert.getNextAttemptAt()).isNull();
        assertThat(alert.getCompletedAt()).isNotNull();
    }

    @Test
    void aTransientFailureBesideANoTokenOneIsStillRetried() {
        AgencyAlert alert = alert(List.of("a@example.com", "b@example.com"), 1);
        prepareClaim(alert);
        when(userInfoRepo.findByUserEmailLowerIn(alert.getRecipientEmails()))
                .thenReturn(List.of(user("a@example.com"), user("b@example.com")));
        when(notifications.sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(new NotificationService.HazardBatchResult(2, 0, 2, "provider unavailable", 1));

        service.dispatchNow(alert.getId());

        assertThat(alert.getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.FAILED);
        assertThat(alert.getNextAttemptAt()).isNotNull();
        assertThat(alert.getCompletedAt()).isNull();
    }

    @Test
    void aRetryPushesOnlyWhoCanStillBeReached_andWritesNoSecondRow() {
        // a: push failed last time (transient)  -> pushed again, no new row
        // b: push delivered last time           -> left alone
        // c: had no token last time             -> left alone (a retry can't fix it)
        // d: never reached (attempt crashed)    -> pushed, gets its first row
        // e: push failed last time, now asleep  -> Lane B, but already holds its row
        AgencyAlert alert = alert(List.of("a@example.com", "b@example.com", "c@example.com",
                "d@example.com", "e@example.com"), 2);
        alert.setOfficialTier("advisory");
        alert.setQueuedAt(java.time.Instant.parse("2026-10-13T12:00:00Z"));
        prepareClaim(alert);
        quietNow("e@example.com");
        when(userInfoRepo.findByUserEmailLowerIn(alert.getRecipientEmails()))
                .thenReturn(List.of(user("e@example.com"), user("d@example.com"), user("c@example.com"),
                        user("b@example.com"), user("a@example.com")));
        when(logRepo.findByTypeAndReferenceIdAndTimestampGreaterThanEqual(
                eq(NotificationService.TYPE_HAZARD_ALERT), eq("agency-alert:17"),
                eq(java.time.Instant.parse("2026-10-13T12:00:00Z"))))
                .thenReturn(List.of(
                        row("A@example.com", false, "provider unavailable", null),
                        row("b@example.com", true, null, null),
                        row("c@example.com", false, "No token", null),
                        row("e@example.com", false, "provider unavailable", null)));
        when(notifications.sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString(),
                any(), anyBoolean(), any(), any(), any()))
                .thenReturn(new NotificationService.HazardBatchResult(2, 2, 0, null, 0));

        service.dispatchNow(alert.getId());

        verify(notifications).sendHazardAlertBatch(
                argThat(l -> l.size() == 2
                        && "a@example.com".equals(l.get(0).getUserEmail())
                        && "d@example.com".equals(l.get(1).getUserEmail())),
                eq("Water advisory"), anyString(), eq("agency-alert:17"), eq("/community/posts/17"),
                isNull(), eq(false), eq(Set.of("a@example.com")), eq(PushPolicyService.Category.AGENCY_ALERT),
                eq(new NotificationService.AlertRowRefs("AGENCY:17", null, "AGENCY:17", null)));
        verify(notifications, never()).logHazardAlertInboxOnly(anyList(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any());
        // Whole-alert view: a, d pushed now; b, e reached earlier; c unreachable.
        assertThat(alert.getAttemptedCount()).isEqualTo(5);
        assertThat(alert.getDeliveredCount()).isEqualTo(4);
        assertThat(alert.getFailedCount()).isEqualTo(1);
        assertThat(alert.getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.PARTIAL);
        assertThat(alert.getNextAttemptAt()).isNull();
    }

    @Test
    void aRetryWithNobodyLeftToTryEndsWithoutAnotherRetry() {
        AgencyAlert alert = alert(List.of("c@example.com"), 2);
        prepareClaim(alert);
        when(userInfoRepo.findByUserEmailLowerIn(alert.getRecipientEmails()))
                .thenReturn(List.of(user("c@example.com")));
        when(logRepo.findByTypeAndReferenceIdAndTimestampGreaterThanEqual(anyString(), anyString(), any()))
                .thenReturn(List.of(row("c@example.com", false, "No token", null)));

        service.dispatchNow(alert.getId());

        verifyNoInteractions(notifications);
        assertThat(alert.getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.FAILED);
        assertThat(alert.getNextAttemptAt()).isNull();
        assertThat(alert.getCompletedAt()).isNotNull();
    }

    private static NotificationLog row(String email, boolean success, String error, String lane) {
        NotificationLog n = new NotificationLog(email, NotificationService.TYPE_HAZARD_ALERT, null,
                "Water advisory", "Use bottled water", "agency-alert:17", "/community/posts/17", null,
                java.time.Instant.parse("2026-10-13T12:01:00Z"), success, error);
        n.setLane(lane);
        return n;
    }

    private void quietNow(String email) {
        UserAlertPreference p = new UserAlertPreference();
        p.setUserEmail(email);
        LocalTime now = LocalTime.now(ZoneOffset.UTC);
        p.setQuietHoursEnabled(true);
        p.setTimezone("UTC");
        p.setQuietStart(now.minusHours(2));
        p.setQuietEnd(now.plusHours(2));
        prefs.put(email, p);
    }

    private void prepareClaim(AgencyAlert alert) {
        when(alertRepo.claimForDispatch(eq(alert.getId()), any(), eq(AgencyAlert.DispatchStatus.SENDING),
                any(), anyInt())).thenAnswer(invocation -> {
            alert.setDispatchStatus(AgencyAlert.DispatchStatus.SENDING);
            alert.setStartedAt(java.time.Instant.now());
            return 1;
        });
        when(alertRepo.findById(alert.getId())).thenReturn(java.util.Optional.of(alert));
        when(alertRepo.save(alert)).thenReturn(alert);
    }

    private static AgencyAlert alert(List<String> emails, int attemptCount) {
        AgencyAlert alert = new AgencyAlert();
        alert.setId(7L);
        alert.setPostId(17L);
        alert.setTitle("Water advisory");
        alert.setBody("Use bottled water");
        alert.setRecipientEmails(emails);
        alert.setRecipientCount(emails.size());
        alert.setAttemptCount(attemptCount);
        return alert;
    }

    private static UserInfo user(String email) {
        UserInfo user = new UserInfo();
        user.setUserEmail(email);
        return user;
    }
}
