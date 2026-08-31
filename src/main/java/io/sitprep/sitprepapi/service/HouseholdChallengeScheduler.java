package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Sends Home's weekly preparedness challenge nudges.
 *
 * <p>This is not the weekly check-in ritual. It points to the existing Home
 * challenge sheet at {@code /?challenge=open}; the frontend decides which
 * hazard-aware drill to render when the user opens the app.</p>
 */
@Service
public class HouseholdChallengeScheduler {

    private static final Logger log = LoggerFactory.getLogger(HouseholdChallengeScheduler.class);

    public static final String TYPE_KICKOFF = "weekly_drill_kickoff";
    public static final String TYPE_NUDGE = "weekly_drill_nudge";

    private static final ZoneId FALLBACK_TZ = ZoneId.of("America/Denver");
    private static final Duration FIRE_WINDOW = Duration.ofMinutes(15);
    private static final String TARGET_URL = "/?challenge=open";

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final UserAlertPreferenceRepo preferenceRepo;
    private final NotificationLogRepo notificationLogRepo;
    private final NotificationService notificationService;

    @Value("${app.householdChallenge.sweepBatchSize:300}")
    private int sweepBatchSize = 300;

    public HouseholdChallengeScheduler(GroupRepo groupRepo,
                                       UserInfoRepo userInfoRepo,
                                       UserAlertPreferenceRepo preferenceRepo,
                                       NotificationLogRepo notificationLogRepo,
                                       NotificationService notificationService) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.preferenceRepo = preferenceRepo;
        this.notificationLogRepo = notificationLogRepo;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT9M")
    public void scheduledSweep() {
        try {
            int sent = sweepOnce(Instant.now());
            if (sent > 0) {
                log.info("HouseholdChallengeScheduler: sent {} weekly drill nudges", sent);
            }
        } catch (Exception e) {
            log.warn("HouseholdChallengeScheduler: tick failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /**
     * Run one sweep. Return value is recipient rows attempted, matching the
     * per-recipient NotificationLog audit model.
     */
    @Transactional
    public int sweepOnce(Instant now) {
        List<Group> households = groupRepo.findHouseholdsForChallengeSweep(
                PageRequest.of(0, Math.max(1, sweepBatchSize)));
        if (households.isEmpty()) return 0;

        int sent = 0;
        for (Group household : households) {
            try {
                sent += maybeSendForHousehold(household, now);
            } catch (Exception inner) {
                log.warn("HouseholdChallengeScheduler: failed household={}: {}",
                        household == null ? "(null)" : household.getGroupId(), inner.getMessage());
                try { Sentry.captureException(inner); } catch (Throwable ignored) {}
            }
        }
        return sent;
    }

    int maybeSendForHousehold(Group household, Instant now) {
        if (household == null || household.getGroupId() == null) return 0;
        if (!HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(household.getGroupType())) {
            return 0;
        }
        if ("active".equalsIgnoreCase(String.valueOf(household.getAlert()))) {
            return 0;
        }

        List<String> memberEmails = normalizedEmails(household.getMemberEmails());
        if (memberEmails.isEmpty()) return 0;

        ZoneId zone = resolveHouseholdZone(memberEmails);
        String weekKey = currentWeekKey(now, zone);
        Map<String, Boolean> progress = household.getChallengeProgress();
        if (progress != null && Boolean.TRUE.equals(progress.get(weekKey))) {
            return 0;
        }

        Slot slot = dueSlot(now, zone).orElse(null);
        if (slot == null) return 0;

        Instant weekStart = weekStartInstant(now, zone);
        List<UserInfo> users = userInfoRepo.findByUserEmailLowerIn(memberEmails);
        if (users == null || users.isEmpty()) return 0;

        String householdName = household.getGroupName() == null || household.getGroupName().isBlank()
                ? "your household"
                : household.getGroupName().trim();
        int sent = 0;
        for (UserInfo user : users) {
            if (user == null || user.getUserEmail() == null) continue;
            if (notificationLogRepo.countByRecipientTypeReferenceSince(
                    user.getUserEmail(), slot.type, household.getGroupId(), weekStart) > 0) {
                continue;
            }
            notificationService.deliverPresenceAwareForGroup(
                    user.getUserEmail(),
                    slot.title,
                    slot.body,
                    householdName,
                    "/images/group-alert-icon.png",
                    slot.type,
                    household.getGroupId(),
                    TARGET_URL,
                    additionalData(household.getGroupId(), weekKey, slot.type),
                    user.getFcmtoken(),
                    household.getGroupId(),
                    Category.WEEKLY_DRILL_REMINDER
            );
            sent++;
        }
        return sent;
    }

    private ZoneId resolveHouseholdZone(List<String> memberEmails) {
        for (String email : memberEmails) {
            Optional<UserAlertPreference> pref = preferenceRepo.findByEmail(email);
            if (pref.isEmpty()) continue;
            String tz = pref.get().getTimezone();
            if (tz == null || tz.isBlank()) continue;
            try {
                return ZoneId.of(tz);
            } catch (Exception ignored) {
                // Try the next member before falling back.
            }
        }
        return FALLBACK_TZ;
    }

    private static Optional<Slot> dueSlot(Instant now, ZoneId zone) {
        ZonedDateTime local = now.atZone(zone);
        for (Slot slot : Slot.values()) {
            if (local.getDayOfWeek() != slot.day) continue;
            LocalTime t = local.toLocalTime();
            if (!t.isBefore(slot.time) && t.isBefore(slot.time.plus(FIRE_WINDOW))) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private static String currentWeekKey(Instant now, ZoneId zone) {
        LocalDate local = now.atZone(zone).toLocalDate();
        int year = local.get(IsoFields.WEEK_BASED_YEAR);
        int week = local.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format(Locale.ROOT, "%04d-W%02d", year, week);
    }

    private static Instant weekStartInstant(Instant now, ZoneId zone) {
        LocalDate monday = now.atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.atStartOfDay(zone).toInstant();
    }

    private static List<String> normalizedEmails(List<String> emails) {
        if (emails == null || emails.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String email : emails) {
            if (email == null || email.isBlank()) continue;
            out.add(email.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String additionalData(String householdId, String weekKey, String slot) {
        return String.format(Locale.ROOT,
                "{\"householdId\":\"%s\",\"weekKey\":\"%s\",\"slot\":\"%s\"}",
                json(householdId), json(weekKey), json(slot));
    }

    private static String json(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private enum Slot {
        KICKOFF(
                DayOfWeek.MONDAY,
                LocalTime.of(9, 0),
                TYPE_KICKOFF,
                "This week's household drill",
                "Tap to start - most drills take about 5 minutes."
        ),
        NUDGE(
                DayOfWeek.THURSDAY,
                LocalTime.of(18, 30),
                TYPE_NUDGE,
                "Still time for this week's drill",
                "Halfway through the week - your household hasn't logged it yet."
        );

        final DayOfWeek day;
        final LocalTime time;
        final String type;
        final String title;
        final String body;

        Slot(DayOfWeek day, LocalTime time, String type, String title, String body) {
            this.day = day;
            this.time = time;
            this.type = type;
            this.title = title;
            this.body = body;
        }
    }
}
