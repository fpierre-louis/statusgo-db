package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
            MealPlanData existing = existingOpt.get();

            // Update Plan Duration and Menu Options Count
            existing.setPlanDuration(mealPlanData.getPlanDuration());
            existing.setNumberOfMenuOptions(mealPlanData.getNumberOfMenuOptions());

            // Clear existing meal plans and update with new ones
            existing.getMealPlan().clear();
            for (MealPlan newMealPlan : mealPlanData.getMealPlan()) {
                newMealPlan.setMealPlanData(existing);  // 🔹 Ensure proper parent-child linkage
                newMealPlan.setId(null);  // 🔹 Allow Hibernate to generate a new ID
                existing.getMealPlan().add(newMealPlan);
            }

            return repository.save(existing);
        } else {
            for (MealPlan mealPlan : mealPlanData.getMealPlan()) {
                mealPlan.setMealPlanData(mealPlanData);  // 🔹 Ensure proper parent-child linkage
                mealPlan.setId(null);  // 🔹 Avoid detached entity error
            }
            return repository.save(mealPlanData);
        }
    }

    public MealPlanData updateMealPlanData(String ownerEmail, MealPlanData mealPlanData) {
        Optional<MealPlanData> existingOpt = repository.findByOwnerEmail(ownerEmail).stream().findFirst();

        if (existingOpt.isPresent()) {
            MealPlanData existing = existingOpt.get();
            existing.setPlanDuration(mealPlanData.getPlanDuration());
            existing.setNumberOfMenuOptions(mealPlanData.getNumberOfMenuOptions());
            existing.getMealPlan().clear();

            for (MealPlan newMealPlan : mealPlanData.getMealPlan()) {
                newMealPlan.setMealPlanData(existing);  // 🔹 Link to parent
                newMealPlan.setId(null);  // 🔹 Prevent detached entity error
                existing.getMealPlan().add(newMealPlan);
            }

            return repository.save(existing);
        } else {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Meal plan not found");
        }
    }

    public boolean deleteMealPlanData(String ownerEmail) {
        Optional<MealPlanData> existingOpt = repository.findByOwnerEmail(ownerEmail).stream().findFirst();

        if (existingOpt.isPresent()) {
            repository.delete(existingOpt.get());
            return true;
        }
        return false;
    }
}
