package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AgencyAlert;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.AgencyAlertRepo;
import io.sitprep.sitprepapi.repo.AgencyAlertDispatchAttemptRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgencyAlertDispatchServiceTest {

    @Mock AgencyAlertRepo alertRepo;
    @Mock AgencyAlertDispatchAttemptRepo attemptRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock NotificationService notifications;
    @Mock TransactionTemplate transactions;
    @Mock TransactionStatus transactionStatus;

    AgencyAlertDispatchService service;

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
        service = new AgencyAlertDispatchService(alertRepo, attemptRepo, userInfoRepo, notifications, transactions);
    }

    @Test
    void zeroRecipientAlertCompletesAsSent() {
        AgencyAlert alert = alert(List.of(), 1);
        prepareClaim(alert);
        when(notifications.sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new NotificationService.HazardBatchResult(0, 0, 0, null));

        service.dispatchNow(alert.getId());

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
        when(notifications.sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString()))
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
        when(notifications.sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new NotificationService.HazardBatchResult(1, 0, 1, "provider unavailable"));

        service.dispatchNow(alert.getId());

        assertThat(alert.getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.FAILED);
        assertThat(alert.getNextAttemptAt()).isNotNull();
        assertThat(alert.getCompletedAt()).isNull();
        verify(attemptRepo).save(argThat(attempt ->
                attempt.getStatus() == AgencyAlert.DispatchStatus.FAILED
                        && attempt.getAttemptNumber() == 2));
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
