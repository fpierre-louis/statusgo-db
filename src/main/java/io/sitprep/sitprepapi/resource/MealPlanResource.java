package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.service.MealPlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/meal-plans")
public class MealPlanResource {
    private final MealPlanService mealPlanService;

    @Autowired
    public MealPlanResource(MealPlanService mealPlanService) {
        this.mealPlanService = mealPlanService;
    }

    @PostMapping
    public ResponseEntity<MealPlan> createMealPlan(@RequestBody MealPlan mealPlan) {
        try {
            System.out.println("Received MealPlan: " + mealPlan);
            MealPlan savedMealPlan = mealPlanService.saveMealPlan(mealPlan);
            return ResponseEntity.ok(savedMealPlan);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }


    @GetMapping("/by-owner/{email}")
    public ResponseEntity<List<MealPlan>> getMealPlansByOwnerEmail(@PathVariable String email) {
        List<MealPlan> mealPlans = mealPlanService.getMealPlansByOwnerEmail(email);
        if (mealPlans.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(mealPlans);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MealPlan> getMealPlanById(@PathVariable Long id) {
        return mealPlanService.getMealPlanById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
