package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface EvacuationPlanRepo extends JpaRepository<EvacuationPlan, Long> {
    List<EvacuationPlan> findByOwnerEmail(String ownerEmail);
    void deleteByOwnerEmail(String ownerEmail); // Delete all plans for a user

    /** Cheap existence check for readiness aggregation. */
    boolean existsByOwnerEmailIgnoreCase(String ownerEmail);

    /**
     * Batched existence lookup. Returns the lower-cased subset of {@code emails}
     * that have at least one EvacuationPlan row.
     */
    @Query("SELECT DISTINCT LOWER(e.ownerEmail) FROM EvacuationPlan e WHERE LOWER(e.ownerEmail) IN :emails")
    Set<String> findOwnerEmailsIn(@Param("emails") Collection<String> emails);
}
