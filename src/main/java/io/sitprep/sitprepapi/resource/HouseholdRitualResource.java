package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.HouseholdRitual;
import io.sitprep.sitprepapi.dto.HouseholdRitualDto;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.HouseholdRitualScheduler;
import io.sitprep.sitprepapi.service.HouseholdRitualService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §4 of {@code docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md} — opt-in
 * household rituals. Round 1 CRUD endpoints.
 *
 * <p>Gating: read access requires household membership; write access
 * (create/delete) requires admin (rituals affect everyone in the
 * household, so the decision sits with the admin per the doc's
 * "opt-in is the line" rule).</p>
 */
@RestController
@RequestMapping("/api/households")
public class HouseholdRitualResource {

    private final HouseholdRitualService ritualService;
    private final HouseholdRitualScheduler scheduler;
    private final HouseholdAccessService access;

    public HouseholdRitualResource(
            HouseholdRitualService ritualService,
            HouseholdRitualScheduler scheduler,
            HouseholdAccessService access
    ) {
        this.ritualService = ritualService;
        this.scheduler = scheduler;
        this.access = access;
    }

    /** List all rituals for a household. Round 1: at most one row. */
    @GetMapping("/{householdId}/rituals")
    public ResponseEntity<List<HouseholdRitualDto>> list(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        List<HouseholdRitualDto> out = ritualService.listForHousehold(householdId).stream()
                .map(HouseholdRitualDto::from)
                .toList();
        return ResponseEntity.ok(out);
    }

    /**
     * Opt in to the weekly check-in ritual, or update an existing
     * one's day/hour/minute. Body is optional and accepts:
     * <ul>
     *   <li>{@code timezone} — IANA tz string, defaults to the service's DEFAULT_TIMEZONE</li>
     *   <li>{@code dayOfWeek} — Java {@link java.time.DayOfWeek} name (MONDAY..SUNDAY); defaults to SUNDAY</li>
     *   <li>{@code hour} — 0..23, defaults to 19</li>
     *   <li>{@code minute} — 0..59, defaults to 0</li>
     * </ul>
     * <p>No-args POST is idempotent (returns existing row unchanged).
     * Body with day/hour/minute is the picker save — overwrites the
     * existing spec without creating a duplicate row.</p>
     */
    @PostMapping("/{householdId}/rituals/weekly-check-in")
    public ResponseEntity<HouseholdRitualDto> createWeeklyCheckIn(
            @PathVariable String householdId,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanAdminHousehold(caller, householdId);
        String timezone = stringField(body, "timezone");
        java.time.DayOfWeek day = parseDayOfWeek(stringField(body, "dayOfWeek"));
        Integer hour = intField(body, "hour");
        Integer minute = intField(body, "minute");
        HouseholdRitual saved = ritualService.createWeeklyCheckIn(
                householdId, caller, timezone, day, hour, minute
        );
        return ResponseEntity.ok(HouseholdRitualDto.from(saved));
    }

    private static String stringField(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Integer intField(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid integer for " + key + ": " + v);
        }
    }

    private static java.time.DayOfWeek parseDayOfWeek(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return java.time.DayOfWeek.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid dayOfWeek: " + raw);
        }
    }

    /**
     * Admin-triggered manual fire. Bypasses the {@code isDueNow}
     * window check so QA can verify the dispatch pipeline at any
     * time without waiting for the scheduled local-time window.
     * Returns 200 with {@code {fired: true}} on success, 200 with
     * {@code {fired: false, reason: "no-ritual"}} if no ritual is
     * opted in yet for this household.
     *
     * <p>Updates {@code lastFiredAt} so a same-day scheduled fire is
     * naturally suppressed (no duplicate). On a different local-day
     * from the scheduled day, the scheduled fire still happens as
     * normal — the test fire just adds an extra fire today.</p>
     */
    @PostMapping("/{householdId}/rituals/weekly-check-in/test-fire")
    public ResponseEntity<Map<String, Object>> testFireWeeklyCheckIn(
            @PathVariable String householdId
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanAdminHousehold(caller, householdId);
        try {
            int result = scheduler.testFire(householdId);
            if (result == 0) {
                return ResponseEntity.ok(Map.of("fired", false, "reason", "no-ritual"));
            }
            return ResponseEntity.ok(Map.of("fired", true));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * §4 R5 — pause the weekly check-in for N weeks (default 1). Body
     * optional: {@code {"weeks": N}}. The scheduler suppresses fires
     * until {@code pausedUntil}. Returns the updated ritual, or 404
     * if no ritual exists.
     */
    @PostMapping("/{householdId}/rituals/weekly-check-in/pause")
    public ResponseEntity<HouseholdRitualDto> pauseWeeklyCheckIn(
            @PathVariable String householdId,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanAdminHousehold(caller, householdId);
        int weeks = 1;
        if (body != null && body.get("weeks") instanceof Number n) {
            weeks = Math.max(1, n.intValue());
        }
        HouseholdRitual saved = ritualService.pause(householdId, weeks);
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No ritual to pause");
        }
        return ResponseEntity.ok(HouseholdRitualDto.from(saved));
    }

    /**
     * §4 R5 — resume immediately. Clears pausedUntil so the next
     * scheduled fire window runs normally. No-op if not paused or no
     * ritual exists.
     */
    @PostMapping("/{householdId}/rituals/weekly-check-in/resume")
    public ResponseEntity<HouseholdRitualDto> resumeWeeklyCheckIn(
            @PathVariable String householdId
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanAdminHousehold(caller, householdId);
        HouseholdRitual saved = ritualService.resume(householdId);
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No ritual to resume");
        }
        return ResponseEntity.ok(HouseholdRitualDto.from(saved));
    }

    /**
     * Delete by ID. Verifies the ritual exists and actually belongs to
     * the household path-parameter before deleting (defense against an
     * admin of household A trying to delete a ritual scoped to
     * household B).
     */
    @DeleteMapping("/{householdId}/rituals/{ritualId}")
    public ResponseEntity<Void> delete(
            @PathVariable String householdId,
            @PathVariable Long ritualId
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanAdminHousehold(caller, householdId);
        Optional<HouseholdRitual> existing = ritualService.findWeeklyCheckIn(householdId);
        if (existing.isEmpty() || !existing.get().getId().equals(ritualId)) {
            // Conservative: return 404 rather than blindly deleting whatever
            // id is passed. Pairs the URL household segment with the ritual
            // row before authorizing the delete.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ritual not found in this household");
        }
        ritualService.delete(ritualId);
        return ResponseEntity.noContent().build();
    }
}
