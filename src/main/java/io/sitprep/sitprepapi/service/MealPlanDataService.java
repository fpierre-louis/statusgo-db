package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public MealPlanData saveOrUpdateMealPlan(MealPlanData mealPlanData) {
        // Check if a meal plan already exists for this user
        List<MealPlanData> existingPlans = repository.findByOwnerEmail(mealPlanData.getOwnerEmail());
        if (!existingPlans.isEmpty()) {
            // Assuming one meal plan per user, get the first one
            MealPlanData existingPlan = existingPlans.get(0);

            // Update the existing plan's details
            existingPlan.setMealPlan(mealPlanData.getMealPlan());
            existingPlan.setPlanDuration(mealPlanData.getPlanDuration());
            existingPlan.setNumberOfMenuOptions(mealPlanData.getNumberOfMenuOptions());

            // Save the updated plan
            return repository.save(existingPlan);
        } else {
            // Create a new plan if none exists
            return repository.save(mealPlanData);
        }
    }

}
