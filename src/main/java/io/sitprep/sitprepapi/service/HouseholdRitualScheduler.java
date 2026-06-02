package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdRitual;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdRitualRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * §4 Round 2 of {@code docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md} —
 * fires the opted-in weekly check-in ritual as a silent inbox nudge
 * (Lane B). Decoupled from the check-in action itself (any member
 * can tap-to-check-in any time via §8); this just adds the recurring
 * delivery channel that surfaces the action on a known cadence.
 *
 * <h2>Schedule semantics</h2>
 *
 * Round 1 supports the single spec {@code WEEKLY_SUN_19:00} — Sunday
 * 7pm in the household's saved timezone. v2's picker UI will extend
 * the parser to cover other day/hour combinations; the sweep loop
 * shape stays the same.
 *
 * <h2>Tick cadence + grace window</h2>
 *
 * Sweep runs every 15 min so the worst-case miss is ~15 min. A fire
 * is "due" if it's currently between 19:00 and 20:00 local in the
 * household's timezone AND we haven't already fired today (idempotency
 * on {@code lastFiredAt}, checked against the same calendar date).
 * Past 20:00 we don't catch up — a Tuesday catchup notification for
 * a Sunday-evening ritual would feel like noise, not a nudge.
 *
 * <h2>Push lane</h2>
 *
 * {@link Category#HOUSEHOLD_RITUAL_REMINDER} → Lane B (silent inbox)
 * via {@link PushPolicyService}. Users opted INTO this; landing it
 * in the inbox without an interruptive push is the right calm-mode
 * shape. Recipient opts OUT by deleting the ritual (admin-only),
 * not by muting a category.
 *
 * <h2>What if a recipient already checked in this week?</h2>
 *
 * Round 1 fires regardless. The fired notification body still reads
 * naturally to a viewer who's already done it ("How's the household
 * this week? — open to see the roster"). Round 3 can suppress per
 * recipient by cross-referencing the {@code weekly-check-in-completed}
 * event log; we keep that optimization for when actual usage shows
 * it matters.
 */
@Service
public class HouseholdRitualScheduler {

    private static final Logger log = LoggerFactory.getLogger(HouseholdRitualScheduler.class);

    /** Sentinel for "this household's timezone wasn't resolvable." */
    private static final ZoneId FALLBACK_TZ = ZoneId.of("America/Denver");

    /** Fire window inside the local day — 19:00 inclusive to 20:00 exclusive. */
    private static final int FIRE_HOUR = 19;
    private static final int FIRE_HOUR_END_EXCLUSIVE = 20;

    private final HouseholdRitualRepo ritualRepo;
    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;

    public HouseholdRitualScheduler(HouseholdRitualRepo ritualRepo,
                                    GroupRepo groupRepo,
                                    UserInfoRepo userInfoRepo,
                                    NotificationService notificationService) {
        this.ritualRepo = ritualRepo;
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
    }

    /**
     * Quarter-hourly tick. Initial delay of 7min keeps the first
     * tick off the boot path while bean wiring settles (and stays
     * distinct from sibling reminder services' initial delays so
     * the first ticks don't pile up).
     */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT7M")
    public void scheduledSweep() {
        try {
            int fired = sweepOnce(Instant.now());
            if (fired > 0) {
                log.info("HouseholdRitualScheduler: fired {} rituals this tick", fired);
            }
        } catch (Exception e) {
            log.warn("HouseholdRitualScheduler: tick failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /**
     * Run one sweep. Public + parameterized on {@code now} for
     * testability and admin-triggered out-of-band runs. Returns the
     * count of rituals fired in this tick.
     */
    @Transactional
    public int sweepOnce(Instant now) {
        List<HouseholdRitual> rituals = ritualRepo.findByKind(HouseholdRitualService.KIND_CHECK_IN);
        if (rituals.isEmpty()) return 0;

        int fired = 0;
        for (HouseholdRitual r : rituals) {
            try {
                if (!isDueNow(r, now)) continue;
                fireWeeklyCheckIn(r);
                r.setLastFiredAt(now);
                r.setUpdatedAt(now);
                ritualRepo.save(r);
                fired++;
            } catch (Exception inner) {
                // Per-ritual failures shouldn't kill the tick — log,
                // capture, and let the next tick try again. lastFiredAt
                // stays unchanged on failure so the same ritual will
                // re-attempt within the same fire window.
                log.warn("HouseholdRitualScheduler: failed for ritual id={} household={}: {}",
                        r.getId(), r.getHouseholdId(), inner.getMessage());
                try { Sentry.captureException(inner); } catch (Throwable ignored) {}
            }
        }
        return fired;
    }

    /**
     * Fire-window predicate. True iff RIGHT NOW in the household's
     * local tz is within the configured fire hour AND the ritual
     * hasn't already fired today (same calendar date in that tz).
     */
    private boolean isDueNow(HouseholdRitual r, Instant now) {
        if (r == null) return false;
        if (!HouseholdRitualService.DEFAULT_SCHEDULE_SPEC.equals(r.getScheduleSpec())) {
            // v2 picker will extend the parser; for now refuse silently
            // to fire anything we don't understand rather than guessing.
            return false;
        }
        ZoneId tz = resolveTz(r.getTimezone());
        ZonedDateTime nowLocal = now.atZone(tz);
        if (nowLocal.getDayOfWeek() != DayOfWeek.SUNDAY) return false;
        int hour = nowLocal.getHour();
        if (hour < FIRE_HOUR || hour >= FIRE_HOUR_END_EXCLUSIVE) return false;
        // Idempotency: don't double-fire on the same Sunday.
        if (r.getLastFiredAt() != null) {
            ZonedDateTime lastLocal = r.getLastFiredAt().atZone(tz);
            if (lastLocal.toLocalDate().equals(nowLocal.toLocalDate())) {
                return false;
            }
        }
        return true;
    }

    private static ZoneId resolveTz(String raw) {
        if (raw == null || raw.isBlank()) return FALLBACK_TZ;
        try {
            return ZoneId.of(raw);
        } catch (Exception e) {
            return FALLBACK_TZ;
        }
    }

    /**
     * Fan out one notification per household member. Lane B silent
     * inbox dispatch via {@link Category#HOUSEHOLD_RITUAL_REMINDER}.
     * Each recipient also gets the existing per-recipient quiet-hours
     * / mute treatment from {@link NotificationService#deliverPresenceAwareForGroup}.
     */
    private void fireWeeklyCheckIn(HouseholdRitual r) {
        Group household = groupRepo.findByGroupId(r.getHouseholdId())
                .filter(g -> HouseholdEventService.HOUSEHOLD_GROUP_TYPE
                        .equalsIgnoreCase(g.getGroupType()))
                .orElse(null);
        if (household == null) {
            log.debug("HouseholdRitualScheduler: household {} missing, skipping fire", r.getHouseholdId());
            return;
        }
        List<String> recipientEmails = household.getMemberEmails();
        if (recipientEmails == null || recipientEmails.isEmpty()) return;

        String householdName = household.getGroupName() != null
                ? household.getGroupName() : "your household";
        String title = "Weekly check-in";
        String body = "How's " + householdName + " this week? One tap to check in.";
        String targetUrl = "/home";
        String referenceId = household.getGroupId();
        String additionalData = String.format(
                "{\"householdId\":\"%s\",\"ritualKind\":\"%s\"}",
                household.getGroupId(),
                r.getKind()
        );

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(recipientEmails);
        for (UserInfo u : users) {
            try {
                String email = u.getUserEmail() == null
                        ? null : u.getUserEmail().toLowerCase(Locale.ROOT);
                if (email == null) continue;
                String token = u.getFcmtoken();
                notificationService.deliverPresenceAwareForGroup(
                        email,
                        title,
                        body,
                        householdName,
                        /* iconUrl */ null,
                        /* notificationType */ "household_ritual_reminder",
                        referenceId,
                        targetUrl,
                        additionalData,
                        token,
                        household.getGroupId(),
                        Category.HOUSEHOLD_RITUAL_REMINDER
                );
            } catch (Exception perRecipient) {
                log.warn("HouseholdRitualScheduler: dispatch failed for {} household={}: {}",
                        u.getUserEmail(), household.getGroupId(), perRecipient.getMessage());
            }
        }

        // Round 2 surfaces the fire via the per-recipient inbox row
        // only. Round 3 can add a first-class KIND_RITUAL_FIRED event
        // so admins see "system fired the nudge" in the chat thread —
        // wire {@link HouseholdEventService#recordSafely} back in then.
        log.debug("HouseholdRitualScheduler: nudge fired for household={} recipients={}",
                household.getGroupId(), users.size());
    }
}
