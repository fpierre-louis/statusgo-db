package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.repo.MealPlanRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class MealPlanService {
    private final MealPlanRepo mealPlanRepo;

    @Autowired
    public MealPlanService(MealPlanRepo mealPlanRepo) {
        this.mealPlanRepo = mealPlanRepo;
    }

    public List<MealPlan> getMealPlansByOwnerEmail(String ownerEmail) {
        return mealPlanRepo.findByOwnerEmail(ownerEmail);
    }

    public Optional<MealPlan> getMealPlanById(Long id) {
        return mealPlanRepo.findById(id);
    }

    public MealPlan saveMealPlan(MealPlan mealPlan) {
        return mealPlanRepo.save(mealPlan);
    }
}
