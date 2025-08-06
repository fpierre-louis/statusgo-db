package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.service.MealPlanDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/mealPlans")
public class MealPlanDataResource {

    private static final Logger logger = LoggerFactory.getLogger(MealPlanDataResource.class);

    @Autowired
    private MealPlanDataService service;

    @Autowired
    private MealPlanDataRepo repository;

    @GetMapping("/{ownerEmail}")
    public ResponseEntity<?> getMealPlansByOwner(@PathVariable String ownerEmail) {
        // REMOVE this method entirely to avoid open access via path
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Direct access not allowed");
    }


    @GetMapping
    public List<MealPlanData> getAllMealPlans() {
        logger.info("Fetching all meal plans.");
        return service.getAllMealPlans();
    }

    @PostMapping
    public MealPlanData saveMealPlan(@RequestBody MealPlanData mealPlanData) {
        logger.info("Received request to save meal plan for: {}", mealPlanData.getOwnerEmail());

        if (mealPlanData.getOwnerEmail() == null || mealPlanData.getMealPlan() == null) {
            logger.error("Invalid meal plan request - missing required fields.");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required fields");
        }

        MealPlanData savedPlan = service.saveMealPlanData(mealPlanData);
        logger.info("Successfully saved meal plan for: {}", mealPlanData.getOwnerEmail());
        return savedPlan;
    }

    @DeleteMapping
    public ResponseEntity<?> deleteMealPlanData() {
        String ownerEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        Optional<MealPlanData> existingOpt = repository.findByOwnerEmail(ownerEmail).stream().findFirst();

        if (existingOpt.isPresent()) {
            repository.delete(existingOpt.get());
            return ResponseEntity.ok("Meal plan deleted successfully.");
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Meal plan not found for this user.");
    }

    @PutMapping
    public MealPlanData updateMealPlan(@RequestBody MealPlanData mealPlanData) {
        String ownerEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        return service.updateMealPlanData(ownerEmail, mealPlanData);
    }
}