package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.EvacuationAdvancedDto;
import io.sitprep.sitprepapi.service.EvacuationAdvancedService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * ADVANCED evacuation readiness (alternate route + offline maps). Standalone
 * advisory endpoint, read-gated to household members — deliberately NOT part of
 * the /api/me readiness payload, so it can never lower baseline readinessPercent
 * (same decoupling guarantee as {@link HomeStockpileResource}).
 */
@RestController
@CrossOrigin(origins = "http://localhost:3000")
public class EvacuationAdvancedResource {

    private final EvacuationAdvancedService service;
    private final HouseholdAccessService access;

    public EvacuationAdvancedResource(EvacuationAdvancedService service,
                                      HouseholdAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/api/households/{householdId}/evacuation/advanced-readiness")
    public ResponseEntity<EvacuationAdvancedDto> advancedReadiness(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(service.getForHousehold(householdId));
    }
}
