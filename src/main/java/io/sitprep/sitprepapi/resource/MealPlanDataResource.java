package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.service.MealPlanDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/mealPlans")
public class MealPlanDataResource {

    @Autowired
    private MealPlanDataService service;

    @GetMapping("/{ownerEmail}")
    public List<MealPlanData> getMealPlansByOwner(@PathVariable String ownerEmail) {
        return service.getMealPlansByOwner(ownerEmail);
    }

    @GetMapping
    public List<MealPlanData> getAllMealPlans() {
        return service.getAllMealPlans();
    }

    @PostMapping
    public MealPlanData saveMealPlan(@RequestBody MealPlanData mealPlanData) {
        if (mealPlanData.getOwnerEmail() == null || mealPlanData.getMealPlan() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required fields");
        }
        return service.saveMealPlanData(mealPlanData);
    }

    @PutMapping("/{ownerEmail}")
    public MealPlanData updateMealPlan(@PathVariable String ownerEmail, @RequestBody MealPlanData updatedData) {
        if (updatedData.getOwnerEmail() == null || updatedData.getMealPlan() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required fields");
        }
        return service.saveMealPlanData(updatedData);
    }
}
