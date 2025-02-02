package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class MealPlanDataService {

    private static final Logger logger = LoggerFactory.getLogger(MealPlanDataService.class); // ✅ Correct SLF4J Logger

    @Autowired
    private MealPlanDataRepo repository;

    public List<MealPlanData> getMealPlansByOwner(String ownerEmail) {
        logger.info("Fetching meal plans for owner: {}", ownerEmail);
        List<MealPlanData> mealPlans = repository.findByOwnerEmail(ownerEmail);
        if (mealPlans.isEmpty()) {
            logger.warn("No meal plans found for owner: {}", ownerEmail);
        }
        return mealPlans;
    }

    public List<MealPlanData> getAllMealPlans() {
        logger.info("Fetching all meal plans");
        return repository.findAll();
    }

    public MealPlanData saveMealPlanData(MealPlanData mealPlanData) {
        logger.info("Saving meal plan for owner: {}", mealPlanData.getOwnerEmail());

        Optional<MealPlanData> existingOpt = repository.findByOwnerEmail(mealPlanData.getOwnerEmail()).stream().findFirst();

        if (existingOpt.isPresent()) {
            MealPlanData existing = existingOpt.get();
            logger.info("Updating existing meal plan for owner: {}", mealPlanData.getOwnerEmail());

            existing.setPlanDuration(mealPlanData.getPlanDuration());
            existing.setNumberOfMenuOptions(mealPlanData.getNumberOfMenuOptions());
            existing.getMealPlan().clear();

            for (MealPlan newMealPlan : mealPlanData.getMealPlan()) {
                newMealPlan.setMealPlanData(existing);
                newMealPlan.setId(null);
                existing.getMealPlan().add(newMealPlan);
            }

            return repository.save(existing);
        } else {
            logger.info("Creating new meal plan for owner: {}", mealPlanData.getOwnerEmail());

            for (MealPlan mealPlan : mealPlanData.getMealPlan()) {
                mealPlan.setMealPlanData(mealPlanData);
                mealPlan.setId(null);
            }
            return repository.save(mealPlanData);
        }
    }

    public MealPlanData updateMealPlanData(String ownerEmail, MealPlanData mealPlanData) {
        logger.info("Updating meal plan for owner: {}", ownerEmail);

        Optional<MealPlanData> existingOpt = repository.findByOwnerEmail(ownerEmail).stream().findFirst();

        if (existingOpt.isPresent()) {
            MealPlanData existing = existingOpt.get();
            existing.setPlanDuration(mealPlanData.getPlanDuration());
            existing.setNumberOfMenuOptions(mealPlanData.getNumberOfMenuOptions());
            existing.getMealPlan().clear();

            for (MealPlan newMealPlan : mealPlanData.getMealPlan()) {
                newMealPlan.setMealPlanData(existing);
                newMealPlan.setId(null);
                existing.getMealPlan().add(newMealPlan);
            }

            return repository.save(existing);
        } else {
            logger.warn("Meal plan not found for owner: {}", ownerEmail);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Meal plan not found");
        }
    }

    public boolean deleteMealPlanData(String ownerEmail) {
        logger.info("Attempting to delete meal plan for owner: {}", ownerEmail);
        Optional<MealPlanData> existingOpt = repository.findByOwnerEmail(ownerEmail).stream().findFirst();

        if (existingOpt.isPresent()) {
            repository.delete(existingOpt.get());
            logger.info("Deleted meal plan for owner: {}", ownerEmail);
            return true;
        }
        logger.warn("Meal plan not found for deletion: {}", ownerEmail);
        return false;
    }
}
