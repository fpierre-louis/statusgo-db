package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.dto.EvacuationPlanDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagSummaryDto;
import io.sitprep.sitprepapi.service.EvacuationPlanService;
import io.sitprep.sitprepapi.service.GoBagService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.HouseholdResolver;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/evacuation-plans")
@CrossOrigin(origins = "http://localhost:3000")
public class EvacuationPlanResource {

    private final EvacuationPlanService evacuationPlanService;
    private final HouseholdAccessService access;
    private final HouseholdResolver householdResolver;
    private final GoBagService goBagService;

    public EvacuationPlanResource(EvacuationPlanService evacuationPlanService,
                                  HouseholdAccessService access,
                                  HouseholdResolver householdResolver,
                                  GoBagService goBagService) {
        this.evacuationPlanService = evacuationPlanService;
        this.access = access;
        this.householdResolver = householdResolver;
        this.goBagService = goBagService;
    }

    /**
     * The plan owner's household go-bag summaries — assembled ONCE per request
     * (one batched bags+items query) and shared across every plan row.
     */
    private List<GoBagSummaryDto> goBagsFor(String ownerEmail) {
        String householdId = householdResolver.baseHouseholdIdFor(ownerEmail);
        return householdId == null ? List.of()
                : goBagService.summariesForHousehold(householdId);
    }

    /**
     * Save multiple plans for a user.
     * Body shape:
     * {
     *   "ownerEmail": "user@example.com",
     *   "evacuationPlans": [ { ...plan fields... } ]
     * }
     */
    @PostMapping("/bulk")
    public ResponseEntity<List<EvacuationPlanDto>> saveAllEvacuationPlans(@RequestBody Map<String, Object> requestData) {
        // Owner is the verified caller — body's ownerEmail (if present) ignored.
        String ownerEmail = AuthUtils.requireAuthenticatedEmail();
        List<Map<String, Object>> plansData = (List<Map<String, Object>>) requestData.get("evacuationPlans");

        if (plansData == null) {
            return ResponseEntity.badRequest().build();
        }

        List<EvacuationPlan> evacuationPlans = plansData.stream().map(data -> {
            EvacuationPlan plan = new EvacuationPlan();
            plan.setOwnerEmail(ownerEmail); // enforce owner from top-level field
            plan.setName((String) data.get("name"));
            plan.setOrigin((String) data.get("origin"));
            plan.setDestination((String) data.get("destination"));
            plan.setDeploy(Boolean.TRUE.equals(data.get("deploy")));
            plan.setShelterName((String) data.get("shelterName"));
            plan.setShelterAddress((String) data.get("shelterAddress"));
            plan.setShelterPhoneNumber((String) data.get("shelterPhoneNumber"));
            plan.setLat(data.get("lat") != null ? ((Number) data.get("lat")).doubleValue() : null);
            plan.setLng(data.get("lng") != null ? ((Number) data.get("lng")).doubleValue() : null);
            plan.setTravelMode((String) data.get("travelMode"));
            plan.setShelterInfo((String) data.get("shelterInfo"));
            return plan;
        }).collect(Collectors.toList());

        List<EvacuationPlan> savedPlans =
                evacuationPlanService.saveAllEvacuationPlans(ownerEmail, evacuationPlans);
        List<GoBagSummaryDto> goBags = goBagsFor(ownerEmail);
        return ResponseEntity.ok(savedPlans.stream()
                .map(p -> EvacuationPlanDto.from(p, goBags)).toList());
    }

    /**
     * Create a SINGLE evacuation plan without replacing the owner's
     * others (the bulk endpoint deletes-and-replaces). Used by the
     * activation surface's "add another shelter".
     */
    @PostMapping("/one")
    public ResponseEntity<EvacuationPlanDto> addOneEvacuationPlan(@RequestBody Map<String, Object> data) {
        String ownerEmail = AuthUtils.requireAuthenticatedEmail();
        EvacuationPlan plan = new EvacuationPlan();
        plan.setOwnerEmail(ownerEmail);
        plan.setName((String) data.get("name"));
        plan.setOrigin((String) data.get("origin"));
        plan.setDestination((String) data.get("destination"));
        plan.setDeploy(Boolean.TRUE.equals(data.get("deploy")));
        plan.setShelterName((String) data.get("shelterName"));
        plan.setShelterAddress((String) data.get("shelterAddress"));
        plan.setShelterPhoneNumber((String) data.get("shelterPhoneNumber"));
        plan.setLat(data.get("lat") != null ? ((Number) data.get("lat")).doubleValue() : null);
        plan.setLng(data.get("lng") != null ? ((Number) data.get("lng")).doubleValue() : null);
        plan.setTravelMode((String) data.get("travelMode"));
        plan.setShelterInfo((String) data.get("shelterInfo"));
        return ResponseEntity.ok(EvacuationPlanDto.from(
                evacuationPlanService.addEvacuationPlan(plan), goBagsFor(ownerEmail)));
    }

    /**
     * Fetch plans for a user. Household plan-sharing has members reading
     * the head's plans, so {@code ownerEmail} can target another user —
     * but only when the caller shares a household with them. Otherwise 403.
     */
    @GetMapping
    public ResponseEntity<List<EvacuationPlanDto>> getEvacuationPlansByOwner(
            @RequestParam("ownerEmail") String ownerEmail) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        if (ownerEmail == null || ownerEmail.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        access.requireCanReadPlanDataFor(caller, ownerEmail);
        List<GoBagSummaryDto> goBags = goBagsFor(ownerEmail);
        return ResponseEntity.ok(evacuationPlanService.getEvacuationPlansByOwner(ownerEmail)
                .stream().map(p -> EvacuationPlanDto.from(p, goBags)).toList());
    }
}
