package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class MealPlanDataService {

    @Autowired
    private MealPlanDataRepo repository;

    public List<MealPlanData> getMealPlansByOwner(String ownerEmail) {
        return repository.findByOwnerEmail(ownerEmail);
    }

    public List<MealPlanData> getAllMealPlans() {
        return repository.findAll();
    }

    public MealPlanData saveMealPlanData(MealPlanData mealPlanData) {
        Optional<MealPlanData> existingOpt = repository.findByOwnerEmail(mealPlanData.getOwnerEmail()).stream().findFirst();

        if (existingOpt.isPresent()) {
            // If existing, update it instead of creating a new one
            MealPlanData existing = existingOpt.get();

            // Update Plan Duration and Menu Options Count
            existing.setPlanDuration(mealPlanData.getPlanDuration());
            existing.setNumberOfMenuOptions(mealPlanData.getNumberOfMenuOptions());

            // Clear old meal plan and replace with new ones
            existing.getMealPlan().clear();

            for (int i = 0; i < mealPlanData.getMealPlan().size(); i++) {
                MealPlan newMealPlan = mealPlanData.getMealPlan().get(i);

                // Set ID to null to ensure a fresh save
                newMealPlan.setId(null);
                existing.getMealPlan().add(newMealPlan);
            }

            return repository.save(existing);
        } else {
            // Ensure IDs start from 1 for a new meal plan
            for (int i = 0; i < mealPlanData.getMealPlan().size(); i++) {
                mealPlanData.getMealPlan().get(i).setId(null);
            }
            return repository.save(mealPlanData);
        }
    }
}

