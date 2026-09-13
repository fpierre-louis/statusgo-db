package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.StandingConditionDtos.*;
import io.sitprep.sitprepapi.service.StandingConditionService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Household Standing Conditions.
 *
 * <p>Household-scoped and AUTHENTICATED — {@code /api/**} is
 * {@code authenticated()} and the service authorizes per household on top of
 * that. These are private household data: they are deliberately not reachable
 * from any {@code permitAll} route, and {@code PublicActivationDto} does not
 * carry them.
 *
 * <p>Reads are open to household members; writes require household owner/admin,
 * matching how the rest of the household PLAN is written. Actor is always
 * token-derived, never a body parameter.
 */
@RestController
@RequestMapping("/api/households/{householdId}/standing-conditions")
public class StandingConditionResource {

    private final StandingConditionService service;

    public StandingConditionResource(StandingConditionService service) {
        this.service = service;
    }

    /** Active conditions, with an `asOf` so a cached copy can date itself. */
    @GetMapping
    public ResponseEntity<StandingConditionsDoc> list(@PathVariable String householdId) {
        return ResponseEntity.ok(service.activeFor(householdId, AuthUtils.requireAuthenticatedEmail()));
    }

    @PostMapping
    public ResponseEntity<StandingConditionDto> create(@PathVariable String householdId,
                                                       @RequestBody StandingConditionRequest req) {
        return ResponseEntity.ok(
                service.create(householdId, AuthUtils.requireAuthenticatedEmail(), req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<StandingConditionDto> update(@PathVariable String householdId,
                                                       @PathVariable Long id,
                                                       @RequestBody StandingConditionRequest req) {
        return ResponseEntity.ok(
                service.update(householdId, id, AuthUtils.requireAuthenticatedEmail(), req));
    }

    /**
     * Clear it. POST rather than DELETE on purpose: the row survives, because
     * "who said this was over, and when" outlives the condition itself.
     */
    @PostMapping("/{id}/clear")
    public ResponseEntity<StandingConditionDto> clear(@PathVariable String householdId,
                                                      @PathVariable Long id) {
        return ResponseEntity.ok(
                service.clear(householdId, id, AuthUtils.requireAuthenticatedEmail()));
    }
}
