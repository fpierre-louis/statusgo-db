package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.service.MealPlanService;
import org.springframework.beans.factory.annotation.Autowired;
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
    public MealPlan createMealPlan(@RequestBody MealPlan mealPlan) {
        return mealPlanService.saveMealPlan(mealPlan);
    }

    @GetMapping
    public List<MealPlan> getAllMealPlans() {
        return mealPlanService.getAllMealPlans();
    }

    @GetMapping("/{id}")
    public ResponseEntity<MealPlan> getMealPlanById(@PathVariable Long id) {
        return mealPlanService.getMealPlanById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<MealPlan> updateMealPlan(@PathVariable Long id, @RequestBody MealPlan mealPlan) {
        if (mealPlanService.getMealPlanById(id).isPresent()) {
            mealPlan.setId(id); // Ensure the ID is set for update
            return ResponseEntity.ok(mealPlanService.saveMealPlan(mealPlan));
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMealPlan(@PathVariable Long id) {
        try {
            mealPlanService.deleteMealPlan(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/admins")
    public ResponseEntity<MealPlan> addAdminsToMealPlan(@PathVariable Long id, @RequestBody List<String> adminEmails) {
        try {
            MealPlan updatedMealPlan = mealPlanService.addAdminsToMealPlan(id, adminEmails);
            return ResponseEntity.ok(updatedMealPlan);
        } catch (IllegalArgumentException e) {
            System.err.println("Error adding admins to meal plan: " + e.getMessage());
            return ResponseEntity.badRequest().body(null);
        }
    }

    @GetMapping("/admin/{email}")
    public List<MealPlan> getMealPlansByAdmin(@PathVariable String email) {
        return mealPlanService.getMealPlansByAdminEmail(email);
    }
}
