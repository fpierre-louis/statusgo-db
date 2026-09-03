package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.PlanActivationDtos.HouseholdActivationsEndedDto;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.PlanActivationService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household-scoped half of the activation lifecycle.
 *
 * <pre>
 *   POST /api/households/{householdId}/activations/end   — "All clear"
 * </pre>
 *
 * <h4>Why this is not {@code POST /api/plans/activations/{id}/end}</h4>
 * That route stays, and the recipient surfaces keep using it: someone reading
 * a shared link holds one activation id and nothing else. But an activation is
 * keyed on the OWNER's email, so a household where two people launched has TWO
 * live rows — and {@code MeService.resolveActiveActivationIdForHome} resolves
 * Home's state by taking the newest across every member. Ending one row lets
 * that resolver fall back to the other: Home stays EVACUATING and the person
 * who declared it over watches it come back.
 *
 * <p>The client cannot paper over that by calling the per-row route N times,
 * because it does not know N. The household is the unit "all clear" is about,
 * so the household is what the route is keyed on.</p>
 *
 * <h4>Membership, not admin</h4>
 * Any member may end it, matching the co-member branch of
 * {@code PlanActivationService.canEnd} and for the same reason: the owner may
 * be the person who is unreachable, which is when this matters most.
 */
@RestController
@RequestMapping("/api/households/{householdId}/activations")
public class HouseholdActivationResource {

    private final PlanActivationService service;
    private final HouseholdAccessService access;

    public HouseholdActivationResource(PlanActivationService service,
                                       HouseholdAccessService access) {
        this.service = service;
        this.access = access;
    }

    /**
     * End every live activation in this household, in one transaction.
     *
     * <p>Idempotent: a household with nothing live returns
     * {@code endedCount: 0} and broadcasts nothing, so a double-tap under
     * stress is free. 403 for a non-member, 404 for an unknown household.</p>
     */
    @PostMapping("/end")
    public ResponseEntity<HouseholdActivationsEndedDto> endAll(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(service.endHouseholdActivations(householdId, caller));
    }
}
