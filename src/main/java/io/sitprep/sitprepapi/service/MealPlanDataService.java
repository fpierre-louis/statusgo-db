package io.sitprep.sitprepapi.resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class MealPlanDataService {

    @Autowired
    private MealPlanDataRepository repository;

    public MealPlanData saveMealPlanData(MealPlanData mealPlanData) {
        return repository.save(mealPlanData);
    }

    public Optional<MealPlanData> getMealPlanData(Long id) {
        return repository.findById(id);
    }
}
