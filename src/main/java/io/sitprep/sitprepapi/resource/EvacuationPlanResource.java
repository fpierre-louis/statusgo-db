package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.service.EvacuationPlanService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
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

    public EvacuationPlanResource(EvacuationPlanService evacuationPlanService,
                                  HouseholdAccessService access) {
        this.evacuationPlanService = evacuationPlanService;
        this.access = access;
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
    public ResponseEntity<List<EvacuationPlan>> saveAllEvacuationPlans(@RequestBody Map<String, Object> requestData) {
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
        return ResponseEntity.ok(savedPlans);
    }

    /**
     * Fetch plans for a user. Household plan-sharing has members reading
     * the head's plans, so {@code ownerEmail} can target another user —
     * but only when the caller shares a household with them. Otherwise 403.
     */
    @GetMapping
    public ResponseEntity<List<EvacuationPlan>> getEvacuationPlansByOwner(
            @RequestParam("ownerEmail") String ownerEmail) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        if (ownerEmail == null || ownerEmail.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        access.requireCanReadPlanDataFor(caller, ownerEmail);
        return ResponseEntity.ok(evacuationPlanService.getEvacuationPlansByOwner(ownerEmail));
    }
}
