package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.WeeklyCheckInDigestDto;
import io.sitprep.sitprepapi.dto.WeeklyCheckInResultDto;
import io.sitprep.sitprepapi.dto.WeeklyCheckInSummaryDto;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.HouseholdEventService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * §8 of {@code docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md} — weekly
 * check-in completion + variable-reward inputs.
 *
 * <p>Decoupled from the §4 ritual scheduler: any household member can
 * complete a weekly check-in at any time. The scheduled nudge (Round 2
 * of §4) is just one delivery channel; this endpoint is the action
 * itself.</p>
 *
 * <ul>
 *   <li>{@code POST /api/households/{id}/check-in/weekly} — record this
 *       caller's check-in, returns event + roster in one round-trip
 *       so the FE can render the reward without a second fetch</li>
 *   <li>{@code GET /api/households/{id}/check-in/weekly/summary} —
 *       roster only, for surfaces that need the "{N} of {M} this week"
 *       count without recording anything (e.g. the home-card row's
 *       sub-copy)</li>
 * </ul>
 *
 * <p>Both endpoints are membership-gated — any household member can
 * record their own check-in OR read the roster.</p>
 */
@RestController
@RequestMapping("/api/households/{householdId}/check-in/weekly")
public class WeeklyCheckInResource {

    private final HouseholdEventService events;
    private final HouseholdAccessService access;

    public WeeklyCheckInResource(
            HouseholdEventService events,
            HouseholdAccessService access
    ) {
        this.events = events;
        this.access = access;
    }

    /**
     * Body shape for the POST. {@code mood} ∈ {@code {"good","needs-help"}}.
     */
    public record Body(String mood) {}

    @PostMapping
    public ResponseEntity<WeeklyCheckInResultDto> record(
            @PathVariable String householdId,
            @RequestBody(required = false) Body body
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        String mood = body == null ? null : body.mood();
        try {
            WeeklyCheckInResultDto result = events.recordWeeklyCheckIn(
                    householdId, caller, mood
            );
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<WeeklyCheckInSummaryDto> summary(
            @PathVariable String householdId
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        try {
            return ResponseEntity.ok(events.summarizeWeeklyCheckIn(householdId, caller));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * §8 R5 — admin-side rollup. Per-member streak counts +
     * this-week roster + the longest-streak member callout.
     * Admin-only because the per-member data set is broader than
     * what a member needs (members see their own streak via the
     * /summary endpoint's actorPosition + viewerStreakWeeks).
     */
    @GetMapping("/digest")
    public ResponseEntity<WeeklyCheckInDigestDto> digest(
            @PathVariable String householdId
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanAdminHousehold(caller, householdId);
        try {
            return ResponseEntity.ok(events.computeWeeklyDigest(householdId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
