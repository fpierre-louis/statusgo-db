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

    public MealPlanData saveMealPlanData(MealPlanData mealPlanData) {
        MealPlanData existing = repository.findByOwnerEmail(mealPlanData.getOwnerEmail())
                .stream()
                .findFirst()
                .orElse(null);
        if (existing != null) {
            // Update fields on the existing entity
            existing.getMealPlan().clear(); // Clear the existing list
            existing.getMealPlan().addAll(mealPlanData.getMealPlan()); // Add new items
            existing.setPlanDuration(mealPlanData.getPlanDuration());
            existing.setNumberOfMenuOptions(mealPlanData.getNumberOfMenuOptions());
            return repository.save(existing);
        }
        return repository.save(mealPlanData); // Create new entry if no existing one
    }



}
