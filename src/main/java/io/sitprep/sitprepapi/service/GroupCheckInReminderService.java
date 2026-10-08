package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.CheckInRollupDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.GroupUrlUtil;
import io.sitprep.sitprepapi.util.GroupNotificationRecipients;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends staged reminder notifications during a group's 48h check-in
 * window. Implements the "atleast 3" cadence the user requested,
 * landing 5 distinct reminders so the family gets nudged at:
 *
 * <ul>
 *   <li>30 min — gentle "check-in started, anyone heard from family?"</li>
 *   <li>4 hr — "still active, 4 hours in"</li>
 *   <li>12 hr — "12 hours in — continue or end?"</li>
 *   <li>24 hr — "day 1 of check-in done — continue or end?"</li>
 *   <li>36 hr — "auto-ends in 12 hours"</li>
 * </ul>
 *
 * <p>At 48h the existing {@link GroupAlertDecayService} takes over,
 * auto-clearing the alert and sending a final "Check-in ended —
 * continue?" notification. This service does NOT itself clear
 * alerts; it only sends nudges and bumps a counter on the Group
 * entity so the same slot doesn't fire twice.</p>
 *
 * <h2>Why a counter instead of "fire when elapsed crosses slot"?</h2>
 *
 * The polling tick is every 15min, so a slot boundary that lands
 * between ticks could be missed (the elapsed time could jump from
 * "25min" → "40min" between two ticks, skipping the 30min mark
 * entirely). The counter pattern "fire all slots whose elapsed-time
 * threshold has been passed AND whose index is greater than the
 * current counter" handles this gracefully — a single tick can
 * catch up multiple missed slots if the service was down.</p>
 *
 * <h2>What if an admin ends the alert mid-window?</h2>
 *
 * {@link GroupService#updateGroupFields} resets the counter to 0
 * AND clears {@code alertActivatedAt} on the Inactive transition.
 * The next tick filters out the row (alertActivatedAt is null OR
 * alert isn't Active), so no stale reminder fires.
 */
@Service
public class GroupCheckInReminderService {

    private static final Logger log = LoggerFactory.getLogger(GroupCheckInReminderService.class);

    /**
     * Reminder slot thresholds in minutes since the alert went Active.
     * Slot index = position in this array. Adding a new slot is a
     * one-line change here; the Group counter naturally tracks the
     * extra position.
     */
    private static final long[] SLOT_MINUTES = { 30, 4 * 60, 12 * 60, 24 * 60, 36 * 60 };

    /**
     * The "auto-ends in 12 hours" slot. It is due 12 hours before the check-in's
     * END ({@code alertExpiresAt}, V90), not 36 hours after its start, so it
     * fires again after "Continue" pushes the end back
     * ({@code GroupService.continueCheckIn} re-arms it).
     */
    public static final int ENDING_SOON_SLOT = 4;
    private static final long ENDING_SOON_BEFORE_MINUTES = 12 * 60;

    /**
     * Slots that ALSO remind the people who haven't answered (owner ruling C-3:
     * 30 minutes and 4 hours in). Later slots are organizer-only.
     */
    private static final int LAST_MEMBER_FOLLOW_UP_SLOT = 1;

    /**
     * Human-readable labels per slot — drives the notification body
     * copy. Kept aligned with {@link #SLOT_MINUTES}.
     */
    private static final String[] SLOT_BODY = {
            "30 minutes in. Anyone you haven't heard from yet?",
            "4 hours into the check-in. Quick family update?",
            "12 hours in. Continue or end the check-in?",
            "Day 1 of the check-in is done. Keep going or wrap up?",
            "Auto-ends in 12 hours. Continue or close it out?"
    };

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;
    private final HouseholdEventService householdEventService;
    private final GroupService groupService;

    @Value("${app.groupAlert.reminderSweepBatchSize:200}")
    private int sweepBatchSize;

    @Value("${app.groupAlert.decayHours:48}")
    private int decayHours = 48;

    public GroupCheckInReminderService(GroupRepo groupRepo,
                                       UserInfoRepo userInfoRepo,
                                       NotificationService notificationService,
                                       HouseholdEventService householdEventService,
                                       GroupService groupService) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
        this.householdEventService = householdEventService;
        this.groupService = groupService;
    }

    /**
     * Quarter-hourly sweep matching the decay service's cadence so a
     * slot boundary is at most ~15min late. Initial delay of 6min keeps
     * the first tick off the boot path while bean wiring settles
     * (slightly different from decay's 5min so the two services
     * don't fire simultaneously and double up DB load).
     */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT6M")
    public void scheduledReminderSweep() {
        try {
            int fired = sweepOnce();
            if (fired > 0) {
                log.info("GroupCheckInReminder: fired {} reminders this tick", fired);
            }
        } catch (Exception e) {
            log.warn("GroupCheckInReminder: tick failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /**
     * Run one reminder pass. Public for testability + admin-triggered
     * out-of-band runs. Returns the total number of reminders sent
     * (1 per group whose elapsed-time crossed a slot since the last tick;
     * up to {@link #SLOT_MINUTES} slots if the service was down for a
     * long stretch and is catching up).
     */
    @Transactional
    public int sweepOnce() {
        List<Group> active = groupRepo.findActiveAlertsForReminderSweep(
                PageRequest.of(0, sweepBatchSize));
        if (active.isEmpty()) return 0;

        Instant now = Instant.now();
        int totalFired = 0;
        for (Group g : active) {
            try {
                int alreadyFired = g.getCheckInRemindersFired() == null
                        ? 0 : g.getCheckInRemindersFired();
                long elapsedMinutes = Duration.between(g.getAlertActivatedAt(), now).toMinutes();
                Instant endsAt = g.getAlertExpiresAt() != null
                        ? g.getAlertExpiresAt()
                        : g.getAlertActivatedAt().plus(Duration.ofHours(decayHours));
                long minutesToEnd = Duration.between(now, endsAt).toMinutes();
                int dueSlot = dueSlotAt(elapsedMinutes, minutesToEnd);
                if (dueSlot < 0 || dueSlot < alreadyFired) {
                    // Either we haven't reached the 1st slot yet, or
                    // we've already fired everything that's due.
                    continue;
                }

                // Catch-up loop: fire every missed slot from the next
                // unfired one up to dueSlot inclusive. Most ticks fire
                // exactly one; a service that was down for hours fires
                // multiple to land the user at the right state.
                for (int slot = alreadyFired; slot <= dueSlot; slot++) {
                    fireReminder(g, slot);
                    totalFired++;
                }
                g.setCheckInRemindersFired(dueSlot + 1);
                groupRepo.save(g);
            } catch (Exception inner) {
                // Per-group failures shouldn't kill the tick — log,
                // capture, and let the next tick try this group again
                // (the counter is unchanged on failure so it'll retry
                // the same slot).
                log.warn("GroupCheckInReminder: failed for group {}: {}",
                        g.getGroupId(), inner.getMessage());
                try { Sentry.captureException(inner); } catch (Throwable ignored) {}
            }
        }
        return totalFired;
    }

    /**
     * Highest slot due, or -1: the elapsed-time slots before
     * {@link #ENDING_SOON_SLOT}, and that one when the END is 12 hours away.
     */
    static int dueSlotAt(long elapsedMinutes, long minutesToEnd) {
        if (minutesToEnd <= ENDING_SOON_BEFORE_MINUTES) return ENDING_SOON_SLOT;
        int idx = -1;
        for (int i = 0; i < ENDING_SOON_SLOT; i++) {
            if (elapsedMinutes >= SLOT_MINUTES[i]) idx = i;
        }
        return idx;
    }

    /**
     * Fan out one reminder to the admins/owner of the group. These are
     * operational prompts to review the active check-in and decide whether
     * to keep it running, so sending them to every member creates noise and
     * exposes controls the member cannot act on. Records a household timeline
     * event when the group is a household so the chat thread reflects the
     * system reminder.
     */
    private void fireReminder(Group group, int slotIndex) {
        List<String> recipientEmails = GroupNotificationRecipients.adminOwnerEmails(group);
        if (recipientEmails.isEmpty()) return;

        String owner = group.getOwnerName() != null ? group.getOwnerName() : "your group leader";
        String title = group.getGroupName();
        String body = SLOT_BODY[Math.min(slotIndex, SLOT_BODY.length - 1)];
        String hazard = group.getActiveHazardType();
        if (hazard != null && !hazard.isBlank()) {
            // Suffix the body with the hazard so a wildfire reminder
            // reads differently from a generic check-in reminder.
            body = body + " (Reason: " + hazard + ")";
        }

        String type = "checkin_reminder";
        String referenceId = group.getGroupId();
        String targetUrl = GroupUrlUtil.getGroupTargetUrl(group);

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(recipientEmails);
        // The slot's question AND the tally. The tally used to REPLACE the
        // question, so "Continue or end the check-in?" and "Auto-ends in 12
        // hours" never reached anyone (ask-to-check-in audit 2026-09-29).
        // Counted by GroupService's check-in rollup, so the push, the rollup
        // endpoint and the drawer's "N of M" agree — manual members included.
        String tally = rollupBody(groupService.checkInRollupFor(group));
        if (tally != null) body = body + " " + tally;
        for (UserInfo user : users) {
            String token = user.getFcmtoken();
            notificationService.deliverPresenceAwareForGroup(
                    user.getUserEmail(),
                    title,
                    body,
                    owner,
                    "/images/group-alert-icon.png",
                    type,
                    referenceId,
                    targetUrl,
                    null,
                    token,
                    group.getGroupId(),
                    Category.CHECK_IN_REVIEW
            );
        }

        if (HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(group.getGroupType())) {
            try {
                householdEventService.recordCheckinReminder(group.getGroupId(), slotIndex);
            } catch (Exception inner) {
                log.warn("GroupCheckInReminder: failed to record event for household {}: {}",
                        group.getGroupId(), inner.getMessage());
            }
        }

        // The people who haven't answered hear about it too, on the first
        // slots only (C-3) — the same nudge, cooldown and "asked" record as an
        // organizer's "ask everyone who hasn't answered".
        int reminded = 0;
        if (slotIndex <= LAST_MEMBER_FOLLOW_UP_SLOT) {
            try {
                reminded = groupService.remindMissing(group);
            } catch (Exception inner) {
                log.warn("GroupCheckInReminder: member follow-up failed for group {}: {}",
                        group.getGroupId(), inner.getMessage());
            }
        }

        log.info("GroupCheckInReminder: fired slot {} for group {} ({} admins/owners, {} members reminded)",
                slotIndex, group.getGroupId(), users.size(), reminded);
    }

    /** "N of M checked in: …" from the check-in rollup, or null for an empty roster. */
    static String rollupBody(CheckInRollupDto r) {
        if (r == null || r.total() == 0) return null;
        return r.accounted() + " of " + r.total() + " checked in: "
                + r.safe() + " safe, " + r.help() + " need help, " + r.injured()
                + " injured, " + r.missing() + " missing. Tap to review.";
    }
}
