package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.MealPlanData;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MealPlanDataRepo extends JpaRepository<MealPlanData, String> {

    /**
     * Case-insensitive lookup by owner email.
     * Each owner should only have one active MealPlanData entry.
     */
    Optional<MealPlanData> findFirstByOwnerEmailIgnoreCase(String ownerEmail);
}
