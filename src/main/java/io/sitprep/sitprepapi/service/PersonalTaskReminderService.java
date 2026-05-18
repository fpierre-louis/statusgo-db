package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Supply-reminder sweep — Phase 1 of {@code BUSINESS_MODEL.md}.
 *
 * <p>Personal preparedness tasks created from a template with a
 * refresh cadence (water every 6 months, batteries every 12, go-bag
 * every 6, fuel weekly) carry a future {@code dueAt}. This daily
 * sweep finds tasks whose {@code dueAt} has passed and which haven't
 * been reminded yet, and fires a gentle notification to the owner:
 * "Time to refresh — your task '{title}' is due."</p>
 *
 * <h2>Fire-exactly-once</h2>
 *
 * Each task is reminded exactly once: the sweep filters on
 * {@code reminderSentAt IS NULL} and stamps that field after firing.
 * No "did I already remind this" bookkeeping beyond the single
 * column. A task that's down-for-maintenance past its due date is
 * caught on the next successful sweep — the query has no lower time
 * bound, so a backlog drains cleanly (oldest-due first).
 *
 * <h2>Why not recurring auto-respawn?</h2>
 *
 * v1 deliberately does "one due-date reminder per task," not
 * "infinitely recurring." When the user re-completes a refreshed
 * supply, a future pass can re-arm a fresh {@code dueAt} +
 * null the {@code reminderSentAt}. Keeping v1 one-shot avoids a
 * respawn loop while the FE task UX settles.
 *
 * <h2>Notification lane</h2>
 *
 * The reminder uses notificationType {@code "task_reminder"}, which
 * is intentionally unmapped in {@code NotificationService}'s category
 * table — it flows through as a no-policy notification (socket when
 * online, FCM when offline, always logged to the inbox). A supply
 * reminder is low-urgency by nature; it doesn't need the critical-
 * bypass or quiet-hours machinery.
 */
@Service
public class PersonalTaskReminderService {

    private static final Logger log =
            LoggerFactory.getLogger(PersonalTaskReminderService.class);

    /** Max tasks reminded per sweep — bounds a catch-up backlog. */
    private static final int SWEEP_BATCH_SIZE = 300;

    private final PostRepo postRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;

    public PersonalTaskReminderService(PostRepo postRepo,
                                       UserInfoRepo userInfoRepo,
                                       NotificationService notificationService) {
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
    }

    /**
     * Daily sweep. {@code fixedDelay} of 24h keeps it once-a-day;
     * the 9-minute initial delay keeps the first run off the boot
     * path (and off the same minute as the other scheduled sweeps so
     * they don't all hammer the DB at once).
     */
    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT9M")
    public void scheduledReminderSweep() {
        try {
            int fired = sweepOnce();
            if (fired > 0) {
                log.info("PersonalTaskReminder: sent {} due-date reminders", fired);
            }
        } catch (Exception e) {
            log.warn("PersonalTaskReminder: sweep failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /**
     * Run one reminder pass. Public for testability + admin-triggered
     * out-of-band runs. Returns the number of reminders sent.
     */
    @Transactional
    public int sweepOnce() {
        Instant now = Instant.now();
        List<Post> due = postRepo.findPersonalTasksDueForReminder(
                Post.PostStatus.OPEN, now, PageRequest.of(0, SWEEP_BATCH_SIZE));
        if (due.isEmpty()) return 0;

        int fired = 0;
        for (Post task : due) {
            try {
                fireReminder(task);
                // Stamp so the task drops out of the query — fire once.
                task.setReminderSentAt(Instant.now());
                postRepo.save(task);
                fired++;
            } catch (Exception e) {
                // One bad row (deleted account, malformed data) must not
                // sink the whole sweep — log and move on. The row keeps
                // reminderSentAt=null and gets retried next sweep.
                log.warn("PersonalTaskReminder: failed for task {}: {}",
                        task.getId(), e.getMessage());
            }
        }
        return fired;
    }

    /**
     * Send the reminder for a single task. Looks up the owner's FCM
     * token so an offline owner still gets the nudge; an online owner
     * gets the in-app socket banner instead (NotificationService picks
     * the lane). Owner with no resolvable UserInfo is skipped.
     */
    private void fireReminder(Post task) {
        String owner = task.getRequesterEmail();
        if (owner == null || owner.isBlank()) return;

        String fcmToken = userInfoRepo.findByUserEmailIgnoreCase(owner)
                .map(UserInfo::getFcmtoken)
                .orElse(null);

        String title = "Time to refresh your prep";
        String taskTitle = task.getTitle() != null ? task.getTitle() : "A preparedness task";
        String body = taskTitle + " is due. Tap to check it off or push it out.";

        notificationService.deliverPresenceAware(
                owner,
                title,
                body,
                /* senderName */ "SitPrep",
                /* iconUrl */ "/images/icon-120.png",
                /* notificationType */ "task_reminder",
                /* referenceId */ String.valueOf(task.getId()),
                /* targetUrl */ "/me/tasks",
                /* additionalData */ null,
                fcmToken
        );
    }
}
