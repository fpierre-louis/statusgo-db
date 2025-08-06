package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import io.sitprep.sitprepapi.util.OwnershipValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class MealPlanDataService {

    private static final Logger logger = LoggerFactory.getLogger(MealPlanDataService.class);

    private final MealPlanDataRepo repository;

    public MealPlanDataService(MealPlanDataRepo repository) {
        this.repository = repository;
    }

    public List<MealPlanData> getMealPlansForCurrentUser() {
        String email = AuthUtils.getCurrentUserEmail();
        logger.info("Fetching meal plans for current user: {}", email);
        List<MealPlanData> mealPlans = repository.findByOwnerEmail(email);
        if (mealPlans.isEmpty()) {
            logger.warn("No meal plans found for user: {}", email);
        }
        return mealPlans;
    }

    public List<MealPlanData> getAllMealPlans() {
        logger.info("Fetching all meal plans");
        return repository.findAll();
    }

    public MealPlanData saveMealPlanData(MealPlanData mealPlanData) {
        String email = AuthUtils.getCurrentUserEmail();
        mealPlanData.setOwnerEmail(email);
        logger.info("Saving meal plan for user: {}", email);

        Optional<MealPlanData> existingOpt = repository.findByOwnerEmail(email).stream().findFirst();

        if (existingOpt.isPresent()) {
            MealPlanData existing = existingOpt.get();
            logger.info("Updating existing meal plan for user: {}", email);

            OwnershipValidator.requireOwnerEmailMatch(existing.getOwnerEmail());

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
            logger.info("Creating new meal plan for user: {}", email);

            for (MealPlan mealPlan : mealPlanData.getMealPlan()) {
                mealPlan.setMealPlanData(mealPlanData);
                mealPlan.setId(null);
            }
            return repository.save(mealPlanData);
        }
    }

    public MealPlanData updateMealPlanData(String ownerEmail, MealPlanData mealPlanData) {
        Optional<MealPlanData> existing = repository.findByOwnerEmail(ownerEmail).stream().findFirst();
        if (existing.isPresent()) {
            MealPlanData existingPlan = existing.get();
            existingPlan.setMealPlan(mealPlanData.getMealPlan());
            return repository.save(existingPlan);
        } else {
            throw new RuntimeException("Meal plan not found for user");
        }
    }


    public boolean deleteCurrentUserMealPlan() {
        String email = AuthUtils.getCurrentUserEmail();
        logger.info("Attempting to delete meal plan for user: {}", email);

        Optional<MealPlanData> existingOpt = repository.findByOwnerEmail(email).stream().findFirst();

        if (existingOpt.isPresent()) {
            OwnershipValidator.requireOwnerEmailMatch(existingOpt.get().getOwnerEmail());
            repository.delete(existingOpt.get());
            logger.info("Deleted meal plan for user: {}", email);
            return true;
        }

        logger.warn("Meal plan not found for deletion: {}", email);
        return false;
    }
}