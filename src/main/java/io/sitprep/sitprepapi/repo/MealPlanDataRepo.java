package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.MealPlanData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MealPlanDataRepo extends JpaRepository<MealPlanData, Long> {
    List<MealPlanData> findByOwnerEmail(String ownerEmail);
}