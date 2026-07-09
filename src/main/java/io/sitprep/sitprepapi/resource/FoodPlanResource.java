package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.FoodPlanDtos.FoodPlanRecommendationDto;
import io.sitprep.sitprepapi.service.FoodPlanCalculatorService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Backend-driven Food Planner (Thin-Client Refactor Phase 2). Returns the
 * fully-computed shopping list for a household — the FE renders it with no
 * client-side serving-size or package math. Read-gated to household members
 * (same gate as {@code HouseholdPlansResource}).
 */
@RestController
@CrossOrigin(origins = "http://localhost:3000")
public class FoodPlanResource {

    private final FoodPlanCalculatorService foodPlanService;
    private final HouseholdAccessService access;

    public FoodPlanResource(FoodPlanCalculatorService foodPlanService,
                            HouseholdAccessService access) {
        this.foodPlanService = foodPlanService;
        this.access = access;
    }

    @GetMapping("/api/households/{householdId}/food-plan")
    public ResponseEntity<FoodPlanRecommendationDto> foodPlan(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(foodPlanService.recommendForHousehold(householdId));
    }
}
