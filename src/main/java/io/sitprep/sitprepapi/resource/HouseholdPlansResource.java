package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.MePlansDto;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.MeService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Household-scoped plan reads — the shared, multi-admin view
 * (docs/WIP_HOUSEHOLD_PLANS.md, Phase 2). Any member of the household can
 * read its combined plan; admins edit via the per-type write endpoints,
 * which now stamp householdId at save time so this view stays current.
 *
 * <p>Mirrors {@code GET /api/me/{uid}/plans} (returns the same
 * {@link MePlansDto} shape) but keyed by householdId instead of the
 * caller's own ownerEmail.</p>
 */
@RestController
@RequestMapping("/api/households")
@CrossOrigin(origins = "http://localhost:3000")
public class HouseholdPlansResource {

    private final MeService meService;
    private final HouseholdAccessService householdAccessService;

    public HouseholdPlansResource(MeService meService,
                                  HouseholdAccessService householdAccessService) {
        this.meService = meService;
        this.householdAccessService = householdAccessService;
    }

    @GetMapping("/{householdId}/plans")
    public ResponseEntity<MePlansDto> getHouseholdPlans(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        householdAccessService.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(meService.buildHouseholdPlans(householdId));
    }
}
