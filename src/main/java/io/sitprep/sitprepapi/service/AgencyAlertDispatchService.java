package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AgencyAlert;
import io.sitprep.sitprepapi.domain.AgencyAlert.DispatchStatus;
import io.sitprep.sitprepapi.domain.AgencyAlertDispatchAttempt;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.AgencyAlertDispatchAttemptRepo;
import io.sitprep.sitprepapi.repo.AgencyAlertRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Durable, post-commit delivery worker for agency jurisdiction alerts.
 *
 * <p><b>Push policy (EXEC-N, 2026-10-07; OWNER REVIEW).</b> Every recipient is
 * evaluated against {@link PushPolicyService.Category#AGENCY_ALERT} with the
 * agency's own tier as the severity. Before this the worker never asked
 * policy, so every tier pushed at any hour and ignored the master push
 * switch. Now:</p>
 * <ul>
 *   <li><b>emergency</b> — critical bypass: pushed inside quiet hours and
 *       exempt from rate caps, like an NWS Severe/Extreme warning.</li>
 *   <li><b>advisory / notice</b> — quiet hours and rate caps defer the push
 *       to an inbox row (marked for the morning catch-up).</li>
 *   <li>Any tier, recipient switched push off — inbox row, no push.</li>
 * </ul>
 * <p>Operator counts: {@code delivered} = pushes FCM accepted + inbox rows
 * written for deferred recipients (the alert reached them durably);
 * {@code failed} = push failures + missing accounts. A recipient whose push
 * AND inbox are both switched off (Lane C) is in neither. A zero-delivery
 * retry can therefore only happen when no inbox row was written.</p>
 */
@Service
public class AgencyAlertDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AgencyAlertDispatchService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Set<DispatchStatus> CLAIMABLE = Set.of(DispatchStatus.QUEUED, DispatchStatus.FAILED);

    private final AgencyAlertRepo alertRepo;
    private final AgencyAlertDispatchAttemptRepo attemptRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notifications;
    private final TransactionTemplate transactions;
    private final PushPolicyService pushPolicy;

    public AgencyAlertDispatchService(AgencyAlertRepo alertRepo,
                                      AgencyAlertDispatchAttemptRepo attemptRepo,
                                      UserInfoRepo userInfoRepo,
                                      NotificationService notifications,
                                      TransactionTemplate transactions,
                                      PushPolicyService pushPolicy) {
        this.alertRepo = alertRepo;
        this.attemptRepo = attemptRepo;
        this.userInfoRepo = userInfoRepo;
        this.notifications = notifications;
        this.transactions = transactions;
        this.pushPolicy = pushPolicy;
    }

    @Scheduled(fixedDelayString = "${agency.alert.dispatch.interval:PT15S}",
            initialDelayString = "${agency.alert.dispatch.initial-delay:PT20S}")
    public void dispatchQueued() {
        Instant now = Instant.now();
        recoverStaleClaims(now);
        List<Long> ids = alertRepo.findDispatchableIds(
                CLAIMABLE, now, MAX_ATTEMPTS, PageRequest.of(0, 20));
        for (Long id : ids) {
            try {
                dispatchNow(id);
            } catch (Exception e) {
                log.error("Agency alert dispatch crashed for {}: {}", id, e.getMessage(), e);
                markUnexpectedFailure(id, e);
            }
        }
    }

    private void recoverStaleClaims(Instant now) {
        transactions.executeWithoutResult(status -> alertRepo.recoverStaleClaims(
                DispatchStatus.SENDING,
                DispatchStatus.FAILED,
                now.minus(Duration.ofMinutes(5)),
                now,
                "Dispatch lease expired; retrying"));
    }

    /** Visible for focused service tests and operational replay. */
    public void dispatchNow(Long alertId) {
        DispatchPayload payload = claim(alertId);
        if (payload == null) return;

        List<UserInfo> users = payload.recipientEmails().isEmpty()
                ? List.of()
                : userInfoRepo.findByUserEmailLowerIn(payload.recipientEmails()).stream()
                        .sorted(Comparator.comparing(
                                user -> user.getUserEmail() == null ? "" : user.getUserEmail()))
                        .toList();
        String body = payload.body() == null ? "" : payload.body();
        String referenceId = "agency-alert:" + payload.postId();
        String targetUrl = "/community/posts/" + payload.postId();

        List<UserInfo> push = new ArrayList<>();
        List<UserInfo> inboxOnly = new ArrayList<>();
        Set<String> quiet = new HashSet<>();
        int suppressed = 0;
        for (UserInfo u : users) {
            PushPolicyService.Decision d = pushPolicy.decide(
                    u.getUserEmail(), PushPolicyService.Category.AGENCY_ALERT, payload.officialTier());
            switch (d.lane()) {
                case A -> push.add(u);
                case B -> {
                    inboxOnly.add(u);
                    if (d.deferredByQuietHours()) {
                        quiet.add(u.getUserEmail().trim().toLowerCase(Locale.ROOT));
                    }
                }
                default -> suppressed++;
            }
        }

        NotificationService.HazardBatchResult result = push.isEmpty()
                ? new NotificationService.HazardBatchResult(0, 0, 0, null)
                : notifications.sendHazardAlertBatch(push, payload.title(), body, referenceId, targetUrl);
        int inboxed = inboxOnly.isEmpty() ? 0
                : notifications.logHazardAlertInboxOnly(inboxOnly, payload.title(), body, referenceId,
                        targetUrl, null, PushPolicyService.Category.AGENCY_ALERT, quiet);
        if (inboxed + suppressed > 0) {
            log.info("Agency alert {} ({}): {} pushed, {} inbox-only, {} suppressed by recipient settings",
                    payload.id(), payload.officialTier(), push.size(), inboxed, suppressed);
        }

        int missingUsers = Math.max(0, payload.recipientEmails().size() - users.size());
        complete(payload, new NotificationService.HazardBatchResult(
                payload.recipientEmails().size(),
                result.delivered() + inboxed,
                result.failed() + missingUsers,
                missingUsers > 0 ? "Recipient account missing" : result.lastError()));
    }

    private DispatchPayload claim(Long alertId) {
        return transactions.execute(status -> {
            Instant now = Instant.now();
            int claimed = alertRepo.claimForDispatch(
                    alertId, CLAIMABLE, DispatchStatus.SENDING, now, MAX_ATTEMPTS);
            if (claimed == 0) return null;
            AgencyAlert alert = alertRepo.findById(alertId).orElse(null);
            if (alert == null) return null;
            return new DispatchPayload(
                    alert.getId(), alert.getPostId(), alert.getTitle(), alert.getBody(),
                    alert.getOfficialTier(),
                    alert.getAttemptCount(), alert.getStartedAt(),
                    List.copyOf(alert.getRecipientEmails()));
        });
    }

    private void complete(DispatchPayload payload, NotificationService.HazardBatchResult result) {
        transactions.executeWithoutResult(status -> {
            AgencyAlert alert = alertRepo.findById(payload.id()).orElse(null);
            if (alert == null || alert.getDispatchStatus() != DispatchStatus.SENDING) return;
            Instant now = Instant.now();
            alert.setAttemptedCount(result.attempted());
            alert.setDeliveredCount(result.delivered());
            alert.setFailedCount(result.failed());
            alert.setLastError(trim(result.lastError()));

            if (result.failed() == 0) {
                alert.setDispatchStatus(DispatchStatus.SENT);
                alert.setCompletedAt(now);
                alert.setDispatchedAt(now);
                alert.setNextAttemptAt(null);
            } else if (result.delivered() > 0) {
                alert.setDispatchStatus(DispatchStatus.PARTIAL);
                alert.setCompletedAt(now);
                alert.setDispatchedAt(now);
                alert.setNextAttemptAt(null);
            } else {
                alert.setDispatchStatus(DispatchStatus.FAILED);
                if (payload.attemptCount() < MAX_ATTEMPTS) {
                    alert.setNextAttemptAt(now.plus(retryDelay(payload.attemptCount())));
                } else {
                    alert.setCompletedAt(now);
                    alert.setNextAttemptAt(null);
                }
            }
            saveAttempt(payload.id(), payload.attemptCount(), payload.startedAt(), now,
                    alert.getDispatchStatus(), result);
            alertRepo.save(alert);
        });
    }

    private void markUnexpectedFailure(Long alertId, Exception error) {
        transactions.executeWithoutResult(status -> alertRepo.findById(alertId).ifPresent(alert -> {
            if (alert.getDispatchStatus() != DispatchStatus.SENDING) return;
            Instant now = Instant.now();
            alert.setDispatchStatus(DispatchStatus.FAILED);
            alert.setLastError(trim(error.getMessage()));
            int intended = alert.getRecipientCount() == null ? 0 : alert.getRecipientCount();
            alert.setAttemptedCount(Math.max(alert.getAttemptedCount(), intended));
            alert.setFailedCount(Math.max(alert.getFailedCount(), intended - alert.getDeliveredCount()));
            if (alert.getAttemptCount() < MAX_ATTEMPTS) {
                alert.setNextAttemptAt(now.plus(retryDelay(alert.getAttemptCount())));
            } else {
                alert.setCompletedAt(now);
                alert.setNextAttemptAt(null);
            }
            NotificationService.HazardBatchResult result = new NotificationService.HazardBatchResult(
                    alert.getAttemptedCount(), alert.getDeliveredCount(), alert.getFailedCount(), error.getMessage());
            saveAttempt(alert.getId(), alert.getAttemptCount(), alert.getStartedAt(), now,
                    alert.getDispatchStatus(), result);
            alertRepo.save(alert);
        }));
    }

    private void saveAttempt(Long alertId,
                             int attemptNumber,
                             Instant startedAt,
                             Instant completedAt,
                             DispatchStatus status,
                             NotificationService.HazardBatchResult result) {
        AgencyAlertDispatchAttempt attempt = new AgencyAlertDispatchAttempt();
        attempt.setAlertId(alertId);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setStatus(status);
        attempt.setAttemptedCount(result.attempted());
        attempt.setDeliveredCount(result.delivered());
        attempt.setFailedCount(result.failed());
        attempt.setLastError(trim(result.lastError()));
        attempt.setStartedAt(startedAt == null ? completedAt : startedAt);
        attempt.setCompletedAt(completedAt);
        attemptRepo.save(attempt);
    }

    private static Duration retryDelay(int attempt) {
        return Duration.ofMinutes(Math.max(1, attempt));
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        return clean.length() <= 1000 ? clean : clean.substring(0, 1000);
    }

    private record DispatchPayload(Long id, Long postId, String title, String body,
                                   String officialTier,
                                   int attemptCount, Instant startedAt,
                                   List<String> recipientEmails) {}
}
