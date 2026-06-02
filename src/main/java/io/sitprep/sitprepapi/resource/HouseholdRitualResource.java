package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.HouseholdRitual;
import io.sitprep.sitprepapi.dto.HouseholdRitualDto;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
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
    private final HouseholdAccessService access;

    public HouseholdRitualResource(
            HouseholdRitualService ritualService,
            HouseholdAccessService access
    ) {
        this.ritualService = ritualService;
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
     * Opt in to the weekly check-in ritual. Body is optional — the FE
     * may pass {@code {"timezone": "America/Los_Angeles"}} so the
     * fire-time is rooted in the household's local TZ; missing falls
     * back to the service's DEFAULT_TIMEZONE. Idempotent — re-posting
     * returns the existing row without creating a duplicate.
     */
    @PostMapping("/{householdId}/rituals/weekly-check-in")
    public ResponseEntity<HouseholdRitualDto> createWeeklyCheckIn(
            @PathVariable String householdId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanAdminHousehold(caller, householdId);
        String timezone = body == null ? null : body.get("timezone");
        HouseholdRitual saved = ritualService.createWeeklyCheckIn(
                householdId, caller, timezone
        );
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
