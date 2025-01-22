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

    public MealPlanData saveMealPlanData(MealPlanData mealPlanData) {
        for (MealPlan plan : mealPlanData.getMealPlan()) {
            if (plan.getId() != null) {
                // Fetch and update existing entity logic (if necessary)
            } else {
                plan.setId(null); // Ensure new entities
            }
        }
        return repository.save(mealPlanData);
    }


}
