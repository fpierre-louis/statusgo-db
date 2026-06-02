package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.HouseholdRitual;
import io.sitprep.sitprepapi.repo.HouseholdRitualRepo;
import io.sitprep.sitprepapi.util.WeeklyScheduleSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * §4 of {@code docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md} — opt-in
 * household rituals. Round 1 ships CRUD only; the scheduled-fire +
 * push-dispatch logic ships in a focused Round 2 commit so the timing
 * + notification side-effects can be tested in isolation.
 *
 * <p>Round 1 supports a single kind: {@code "check-in"} with the
 * constant schedule spec {@code "WEEKLY_SUN_19:00"} (Sunday 7pm in
 * the user's local timezone). v2's picker UI extends the
 * service-layer parsing of scheduleSpec to cover day-of-week +
 * hour-of-day combinations.</p>
 */
@Service
public class HouseholdRitualService {

    private static final Logger log = LoggerFactory.getLogger(HouseholdRitualService.class);

    public static final String KIND_CHECK_IN = "check-in";
    public static final String DEFAULT_SCHEDULE_SPEC = "WEEKLY_SUN_19:00";
    public static final String DEFAULT_TIMEZONE = "America/Denver";

    private final HouseholdRitualRepo repo;

    public HouseholdRitualService(HouseholdRitualRepo repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<HouseholdRitual> listForHousehold(String householdId) {
        return repo.findByHouseholdId(householdId);
    }

    @Transactional(readOnly = true)
    public Optional<HouseholdRitual> findWeeklyCheckIn(String householdId) {
        return repo.findFirstByHouseholdIdAndKind(householdId, KIND_CHECK_IN);
    }

    /**
     * Idempotent opt-in with the default Sunday-7pm schedule.
     * Convenience wrapper around the day/hour-aware overload.
     */
    @Transactional
    public HouseholdRitual createWeeklyCheckIn(
            String householdId,
            String optedInByEmail,
            String timezone
    ) {
        return createWeeklyCheckIn(householdId, optedInByEmail, timezone,
                /* dayOfWeek */ null, /* hour */ null, /* minute */ null);
    }

    /**
     * Idempotent opt-in with explicit day/hour/minute. If a check-in
     * ritual already exists for this household:
     *   • return it unchanged when no day/hour/minute were supplied
     *     (matches the no-args opt-in semantics — re-tapping the
     *     "Set weekly check-in" button doesn't bulldoze custom timing),
     *   • UPDATE its scheduleSpec when day/hour/minute ARE supplied
     *     (matches the picker UI semantics — saving a new time
     *     overwrites the existing one).
     *
     * <p>Idempotency matters because the FE may double-tap; we want
     * the no-args path to be safe on double-tap. The explicit-args
     * path is the picker save, which IS supposed to mutate.</p>
     *
     * <p>Null day → SUNDAY. Null hour → 19. Null minute → 0. Matches
     * the prior hardcoded {@link #DEFAULT_SCHEDULE_SPEC}.</p>
     */
    @Transactional
    public HouseholdRitual createWeeklyCheckIn(
            String householdId,
            String optedInByEmail,
            String timezone,
            DayOfWeek dayOfWeek,
            Integer hour,
            Integer minute
    ) {
        DayOfWeek day = dayOfWeek == null ? DayOfWeek.SUNDAY : dayOfWeek;
        int h = hour == null ? 19 : Math.max(0, Math.min(23, hour));
        int m = minute == null ? 0 : Math.max(0, Math.min(59, minute));
        boolean explicit = dayOfWeek != null || hour != null || minute != null;
        String spec = WeeklyScheduleSpec.of(day, h, m).toSpecString();

        Optional<HouseholdRitual> existing = repo.findFirstByHouseholdIdAndKind(
                householdId, KIND_CHECK_IN
        );
        if (existing.isPresent()) {
            HouseholdRitual r = existing.get();
            if (!explicit) {
                log.debug("HouseholdRitual: opt-in no-op for household={} (already exists)", householdId);
                return r;
            }
            // Picker save — mutate the existing row's spec/tz instead
            // of creating a second one. Preserve lastFiredAt so a
            // schedule change doesn't accidentally re-fire today.
            r.setScheduleSpec(spec);
            if (timezone != null && !timezone.isBlank()) r.setTimezone(timezone);
            r.setUpdatedAt(Instant.now());
            HouseholdRitual saved = repo.save(r);
            log.info("HouseholdRitual: updated check-in spec for household={} → {}",
                    householdId, spec);
            return saved;
        }
        HouseholdRitual r = new HouseholdRitual();
        r.setHouseholdId(householdId);
        r.setKind(KIND_CHECK_IN);
        r.setScheduleSpec(spec);
        r.setTimezone(timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone);
        r.setOptedInByEmail(optedInByEmail);
        Instant now = Instant.now();
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        HouseholdRitual saved = repo.save(r);
        log.info("HouseholdRitual: created check-in for household={} by={} spec={}",
                householdId, optedInByEmail, spec);
        return saved;
    }

    /**
     * Delete by ID. Caller (resource) verifies the ritual belongs to
     * the household path-parameter before invoking — keeps the service
     * agnostic to the URL shape.
     */
    @Transactional
    public void delete(Long ritualId) {
        repo.deleteById(ritualId);
    }
}
