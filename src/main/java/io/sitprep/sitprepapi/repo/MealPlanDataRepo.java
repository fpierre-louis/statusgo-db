package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.MealPlanData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface MealPlanDataRepo extends JpaRepository<MealPlanData, String> {

    /**
     * Case-insensitive lookup by owner email.
     * Each owner should only have one active MealPlanData entry.
     */
    Optional<MealPlanData> findFirstByOwnerEmailIgnoreCase(String ownerEmail);

    /** Cheap existence check for readiness aggregation. */
    boolean existsByOwnerEmailIgnoreCase(String ownerEmail);

    /**
     * Batched existence lookup. Returns the lower-cased subset of {@code emails}
     * that have at least one MealPlanData row.
     */
    @Query("SELECT DISTINCT LOWER(m.ownerEmail) FROM MealPlanData m WHERE LOWER(m.ownerEmail) IN :emails")
    Set<String> findOwnerEmailsIn(@Param("emails") Collection<String> emails);
}
