package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One-time reminder for anonymous guest accounts before Firebase's
 * 30-day auto-cleanup window closes.
 */
@Service
public class GuestExpiryReminderService {

    private static final Logger log =
            LoggerFactory.getLogger(GuestExpiryReminderService.class);

    private static final Duration GUEST_TTL = Duration.ofDays(30);
    private static final Duration REMIND_BEFORE_EXPIRY = Duration.ofDays(7);
    private static final int SWEEP_BATCH_SIZE = 300;

    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;

    public GuestExpiryReminderService(UserInfoRepo userInfoRepo,
                                      NotificationService notificationService) {
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT11M")
    public void scheduledReminderSweep() {
        try {
            int sent = sweepOnce();
            if (sent > 0) {
                log.info("GuestExpiryReminder: sent {} guest expiry reminders", sent);
            }
        } catch (Exception e) {
            log.warn("GuestExpiryReminder: sweep failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    @Transactional
    public int sweepOnce() {
        Instant now = Instant.now();
        Instant reminderCutoff = now.minus(GUEST_TTL.minus(REMIND_BEFORE_EXPIRY));
        Instant expiredCutoff = now.minus(GUEST_TTL);

        List<UserInfo> candidates =
                userInfoRepo.findGuestAccountsNeedingExpiryReminder(
                        reminderCutoff,
                        expiredCutoff,
                        PageRequest.of(0, SWEEP_BATCH_SIZE));
        if (candidates.isEmpty()) return 0;

        int sent = 0;
        for (UserInfo user : candidates) {
            try {
                sendReminder(user);
                user.setGuestExpiryReminderSentAt(now);
                userInfoRepo.save(user);
                sent++;
            } catch (Exception e) {
                log.warn("GuestExpiryReminder: failed for user {}: {}",
                        user.getId(), e.getMessage());
            }
        }
        return sent;
    }

    private void sendReminder(UserInfo user) {
        if (user == null) return;
        String token = user.getFcmtoken();
        if (token == null || token.isBlank()) return;

        String title = "Guest data expires soon";
        String body = "Sign in within 7 days to keep your SitPrep plans, household, and supplies.";

        notificationService.deliverPresenceAware(
                user.getUserEmail(),
                title,
                body,
                "SitPrep",
                "/images/icon-120.png",
                "guest_expiry_reminder",
                user.getFirebaseUid(),
                "/login",
                "{\"daysLeft\":7}",
                token
        );
    }
}
