package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.HouseholdRitual;
import io.sitprep.sitprepapi.repo.HouseholdRitualRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * Idempotent opt-in. If a check-in ritual already exists for this
     * household, returns it without creating a duplicate; otherwise
     * creates the canonical Sunday-7pm ritual in the caller's chosen
     * timezone (or {@link #DEFAULT_TIMEZONE} as fallback).
     *
     * <p>Idempotency matters because the FE may double-tap; we want
     * "opt-in then immediately verify" to land on the same row both
     * times rather than creating duplicates.</p>
     */
    @Transactional
    public HouseholdRitual createWeeklyCheckIn(
            String householdId,
            String optedInByEmail,
            String timezone
    ) {
        Optional<HouseholdRitual> existing = repo.findFirstByHouseholdIdAndKind(
                householdId, KIND_CHECK_IN
        );
        if (existing.isPresent()) {
            log.debug("HouseholdRitual: opt-in no-op for household={} (already exists)", householdId);
            return existing.get();
        }
        HouseholdRitual r = new HouseholdRitual();
        r.setHouseholdId(householdId);
        r.setKind(KIND_CHECK_IN);
        r.setScheduleSpec(DEFAULT_SCHEDULE_SPEC);
        r.setTimezone(timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone);
        r.setOptedInByEmail(optedInByEmail);
        Instant now = Instant.now();
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        HouseholdRitual saved = repo.save(r);
        log.info("HouseholdRitual: created check-in for household={} by={}",
                householdId, optedInByEmail);
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
