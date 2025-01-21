package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.MealPlan;
import org.springframework.data.jpa.repository.JpaRepository;


import java.util.List;
public interface MealPlanRepo extends JpaRepository<MealPlan, Long> {
    List<MealPlan> findByOwnerEmail(String ownerEmail);
}
