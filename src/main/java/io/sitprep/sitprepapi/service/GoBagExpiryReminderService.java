package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.GoBag;
import io.sitprep.sitprepapi.domain.GoBagItem;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GoBagItemRepo;
import io.sitprep.sitprepapi.repo.GoBagRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Go-bag rotation reminders — the "your water pouches expire soon" sweep.
 * Clone of {@link PersonalTaskReminderService} (the proven template), per
 * {@code docs/SCHEDULED_JOBS.md} conventions: {@code fixedDelay},
 * fire-exactly-once latch, Sentry-captured failures, batched idempotent
 * ticks.
 *
 * <h2>One notification per household, not per item</h2>
 *
 * The 5-year food/water cliff means many items in a bag expire in the same
 * window. Firing one push per item would be notification spam (VISION_AND_SCOPE
 * integrity rule 7). Instead the sweep groups the due items by household and
 * sends a single grouped nudge ("3 go-bag items need attention — water
 * pouches, ration bars, ibuprofen") to the household's owner + admins, then
 * stamps {@code reminderSentAt} on every included item in the same transaction.
 *
 * <h2>Re-arming</h2>
 *
 * Tapping "Mark refreshed" (or editing the expiry date) nulls
 * {@code reminderSentAt}, so a replaced item re-enters the sweep on its next
 * cliff. The semi-annual full-bag check ("clocks change → check your kit")
 * rides the separate personal-task template engine, not this service.
 */
@Service
public class GoBagExpiryReminderService {

    private static final Logger log =
            LoggerFactory.getLogger(GoBagExpiryReminderService.class);

    /** Max items processed per tick — bounds a catch-up backlog. */
    private static final int SWEEP_BATCH_SIZE = 300;

    private final GoBagItemRepo itemRepo;
    private final GoBagRepo bagRepo;
    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;

    public GoBagExpiryReminderService(GoBagItemRepo itemRepo,
                                      GoBagRepo bagRepo,
                                      GroupRepo groupRepo,
                                      UserInfoRepo userInfoRepo,
                                      NotificationService notificationService) {
        this.itemRepo = itemRepo;
        this.bagRepo = bagRepo;
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
    }

    /**
     * Daily sweep. {@code PT24H} is plenty for a 30-day-horizon reminder; the
     * 13-minute initial delay staggers it off the boot path and off the other
     * scheduled sweeps' minutes (PT9M/PT10M/PT11M).
     */
    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT13M")
    public void scheduledExpirySweep() {
        try {
            int households = sweepOnce();
            if (households > 0) {
                log.info("GoBagExpiryReminder: notified {} household(s) of expiring items", households);
            }
        } catch (Exception e) {
            log.warn("GoBagExpiryReminder: sweep failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /**
     * Run one pass. Public for testability + admin-triggered runs. Returns the
     * number of households notified.
     */
    @Transactional
    public int sweepOnce() {
        LocalDate horizon = LocalDate.now()
                .plusDays(GoBagRecommendationService.EXPIRY_WARNING_DAYS);
        List<GoBagItem> due = itemRepo.findDueForExpiryReminder(
                horizon, PageRequest.of(0, SWEEP_BATCH_SIZE));
        if (due.isEmpty()) return 0;

        // Group due items by household (via their bag). Preserve encounter
        // order so the summary lists the nearest-expiring items first.
        Map<String, List<GoBagItem>> byHousehold = new LinkedHashMap<>();
        for (GoBagItem item : due) {
            GoBag bag = bagRepo.findById(item.getBagId()).orElse(null);
            if (bag == null || bag.getHouseholdId() == null) continue;
            byHousehold.computeIfAbsent(bag.getHouseholdId(), k -> new ArrayList<>()).add(item);
        }

        int notified = 0;
        Instant stampedAt = Instant.now();
        for (Map.Entry<String, List<GoBagItem>> e : byHousehold.entrySet()) {
            List<GoBagItem> items = e.getValue();
            int outcome;
            try {
                outcome = notifyHousehold(e.getKey(), items);
            } catch (Exception ex) {
                // A transient send failure must NOT stamp the fire-once latch —
                // leave reminderSentAt=null so the items retry next tick.
                log.warn("GoBagExpiryReminder: notify failed for household {}: {}",
                        e.getKey(), ex.getMessage());
                continue;
            }
            // outcome: DELIVERED (1) → stamp + count; ORPHANED (-1, household
            // gone → nobody to notify ever) → stamp so it stops retrying but
            // don't count as a real notification; NO_RECIPIENTS (0, household
            // exists but has no owner/admin yet) → do NOT stamp, retry next
            // tick so a later-added admin still gets the safety reminder.
            if (outcome != NO_RECIPIENTS) {
                items.forEach(i -> i.setReminderSentAt(stampedAt));
                itemRepo.saveAll(items);
            }
            if (outcome == DELIVERED) notified++;
        }
        return notified;
    }

    private static final int DELIVERED = 1;
    private static final int NO_RECIPIENTS = 0;
    private static final int ORPHANED = -1;

    /**
     * @return {@link #DELIVERED} if ≥1 recipient was notified (push/inbox),
     *         {@link #ORPHANED} if the household no longer resolves (stamp to
     *         stop retrying dead data), or {@link #NO_RECIPIENTS} if the
     *         household exists but has no owner/admin (leave unstamped to retry).
     */
    private int notifyHousehold(String householdId, List<GoBagItem> items) {
        Group hh = groupRepo.findByGroupId(householdId).orElse(null);
        if (hh == null) return ORPHANED;

        Set<String> recipients = new LinkedHashSet<>();
        if (hh.getOwnerEmail() != null && !hh.getOwnerEmail().isBlank()) {
            recipients.add(hh.getOwnerEmail().trim().toLowerCase(Locale.ROOT));
        }
        if (hh.getAdminEmails() != null) {
            hh.getAdminEmails().forEach(a -> {
                if (a != null && !a.isBlank()) recipients.add(a.trim().toLowerCase(Locale.ROOT));
            });
        }
        if (recipients.isEmpty()) return NO_RECIPIENTS;

        String title = "Time to refresh your go bag";
        String body = buildBody(items);

        int delivered = 0;
        for (String email : recipients) {
            // Per-recipient isolation: one recipient's failure must not re-notify
            // the ones that already succeeded (which stamping-on-throw would cause).
            try {
                String fcmToken = userInfoRepo.findByUserEmailIgnoreCase(email)
                        .map(UserInfo::getFcmtoken)
                        .orElse(null);
                notificationService.deliverPresenceAware(
                        email,
                        title,
                        body,
                        /* senderName */ "SitPrep",
                        /* iconUrl */ "/images/icon-120.png",
                        /* notificationType */ "gobag_expiry",
                        /* referenceId */ householdId,
                        /* targetUrl */ "/go-bag",
                        /* additionalData */ null,
                        fcmToken
                );
                delivered++;
            } catch (Exception ex) {
                log.warn("GoBagExpiryReminder: deliver to {} failed: {}", email, ex.getMessage());
            }
        }
        // At least one recipient got it → safe to stamp. Zero delivered (every
        // recipient threw) → treat as no-recipients so we retry.
        return delivered > 0 ? DELIVERED : NO_RECIPIENTS;
    }

    /** "3 go-bag items need attention — Water, Ration bars, and 1 more." */
    private String buildBody(List<GoBagItem> items) {
        int n = items.size();
        List<String> names = items.stream().map(GoBagItem::getLabel).limit(2).toList();
        String lead = String.join(", ", names);
        if (n == 1) {
            return items.get(0).getLabel() + " is expiring soon. Tap to check and rotate it.";
        }
        if (n == 2) {
            return n + " go-bag items are expiring soon — " + lead + ". Tap to rotate them.";
        }
        return n + " go-bag items are expiring soon — " + lead
                + ", and " + (n - 2) + " more. Tap to rotate them.";
    }
}
