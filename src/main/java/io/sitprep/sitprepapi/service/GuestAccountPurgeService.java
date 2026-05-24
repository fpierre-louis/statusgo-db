package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Hard-deletes anonymous guest accounts whose 30-day TTL has elapsed.
 *
 * <p>Firebase auto-deletes the anonymous <i>auth</i> user at
 * creationTime + 30 days, but nothing removed the backend
 * {@link UserInfo} + cascaded plan / household / contact data — so guest
 * rows orphaned and persisted indefinitely. That made the user-facing
 * "expires in 30 days" promise untrue and slowly bloated the DB.</p>
 *
 * <p>This keeps the promise honest and reuses
 * {@link AccountDeletionService#deleteAccount(String)} so the purge
 * cascade matches manual ("delete my account") deletion exactly. Guests
 * with a solo household delete cleanly; the rare guest who owns a
 * multi-member group is skipped (logged) rather than silently stripping
 * other members. Converted users never match the query — conversion
 * clears {@code guestAccount}.</p>
 *
 * <p>Pairs with {@link GuestExpiryReminderService}, which pushes a
 * 7-days-before warning so the user can sign in and keep their work.</p>
 */
@Service
public class GuestAccountPurgeService {

    private static final Logger log =
            LoggerFactory.getLogger(GuestAccountPurgeService.class);

    private static final Duration GUEST_TTL = Duration.ofDays(30);
    private static final int PURGE_BATCH_SIZE = 200;

    private final UserInfoRepo userInfoRepo;
    private final AccountDeletionService accountDeletionService;

    public GuestAccountPurgeService(UserInfoRepo userInfoRepo,
                                    AccountDeletionService accountDeletionService) {
        this.userInfoRepo = userInfoRepo;
        this.accountDeletionService = accountDeletionService;
    }

    // Daily at 03:50 UTC — staggered after the retention sweeps (03:30 /
    // 03:35) and the guest reminder sweep so the nightly jobs don't pile up.
    @Scheduled(cron = "0 50 3 * * *", zone = "UTC")
    public void scheduledGuestPurge() {
        try {
            int purged = purgeOnce();
            if (purged > 0) {
                log.info("GuestPurge: hard-deleted {} expired guest account(s)", purged);
            }
        } catch (Exception e) {
            log.warn("GuestPurge: sweep failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /**
     * Delete one batch of expired guests.
     *
     * <p>Deliberately NOT {@code @Transactional}: each
     * {@link AccountDeletionService#deleteAccount} call already runs in
     * its own transaction, so one bad row can't roll back the whole
     * batch.</p>
     *
     * @return number of guest accounts actually deleted this run
     */
    public int purgeOnce() {
        Instant expiredCutoff = Instant.now().minus(GUEST_TTL);
        List<UserInfo> expired = userInfoRepo.findExpiredGuestAccounts(
                expiredCutoff, PageRequest.of(0, PURGE_BATCH_SIZE));
        if (expired.isEmpty()) return 0;

        int purged = 0;
        for (UserInfo guest : expired) {
            String email = guest.getUserEmail();
            if (email == null || email.isBlank()) {
                // Guests get a placeholder email at provisioning, so this
                // shouldn't happen — skip defensively rather than guess.
                log.warn("GuestPurge: expired guest {} has no email; skipping", guest.getId());
                continue;
            }
            try {
                accountDeletionService.deleteAccount(email);
                purged++;
            } catch (AccountDeletionService.OwnedGroupsBlockingException blocked) {
                log.warn("GuestPurge: skipped guest {} — owns a multi-member group; needs manual review",
                        guest.getId());
            } catch (Exception e) {
                log.warn("GuestPurge: delete failed for guest {}: {}", guest.getId(), e.getMessage());
            }
        }
        return purged;
    }
}
