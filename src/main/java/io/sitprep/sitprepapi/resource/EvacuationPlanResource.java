package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.service.EvacuationPlanService;
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

    public EvacuationPlanResource(EvacuationPlanService evacuationPlanService) {
        this.evacuationPlanService = evacuationPlanService;
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<EvacuationPlan>> saveAllEvacuationPlans(@RequestBody Map<String, Object> requestData) {
        String ownerEmail = (String) requestData.get("ownerEmail");
        List<Map<String, Object>> plansData = (List<Map<String, Object>>) requestData.get("evacuationPlans");

        if (ownerEmail == null || plansData == null || plansData.isEmpty()) {
            return ResponseEntity.badRequest().body(null);
        }

        List<EvacuationPlan> evacuationPlans = plansData.stream().map(data -> {
            EvacuationPlan plan = new EvacuationPlan();
            plan.setOwnerEmail(ownerEmail);
            plan.setName((String) data.get("name"));
            plan.setOrigin((String) data.get("origin"));
            plan.setDestination((String) data.get("destination"));
            plan.setDeploy(Boolean.TRUE.equals(data.get("deploy")));

            // Handling shelterDetails
            Map<String, Object> shelterDetails = (Map<String, Object>) data.get("shelterDetails");
            if (shelterDetails != null) {
                plan.setShelterName((String) shelterDetails.getOrDefault("name", ""));
                plan.setShelterAddress((String) shelterDetails.getOrDefault("address", ""));
                plan.setShelterPhoneNumber((String) shelterDetails.getOrDefault("phoneNumber", ""));

                Map<String, Object> latLng = (Map<String, Object>) shelterDetails.get("latLng");
                if (latLng != null) {
                    plan.setLat((Double) latLng.get("lat"));
                    plan.setLng((Double) latLng.get("lng"));
                }
            }
            return plan;
        }).collect(Collectors.toList());

        List<EvacuationPlan> savedPlans = evacuationPlanService.saveAllEvacuationPlans(ownerEmail, evacuationPlans);
        return ResponseEntity.ok(savedPlans);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getEvacuationPlansByOwner(@RequestParam String ownerEmail) {
        List<EvacuationPlan> plans = evacuationPlanService.getEvacuationPlansByOwner(ownerEmail);

        // Transform the response to match frontend expectations
        List<Map<String, Object>> formattedPlans = plans.stream().map(plan -> Map.of(
                "id", plan.getId(),
                "ownerEmail", plan.getOwnerEmail(),
                "name", plan.getName(),
                "origin", plan.getOrigin(),
                "destination", plan.getDestination(),
                "deploy", plan.isDeploy(),
                "shelterDetails", Map.of(
                        "name", plan.getShelterName(),
                        "address", plan.getShelterAddress(),
                        "phoneNumber", plan.getShelterPhoneNumber(),
                        "latLng", plan.getLat() != null && plan.getLng() != null ?
                                Map.of("lat", plan.getLat(), "lng", plan.getLng()) : null
                )
        )).collect(Collectors.toList());

        return ResponseEntity.ok(formattedPlans);
    }
}
