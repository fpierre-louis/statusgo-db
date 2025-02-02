package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvacuationPlanRepo extends JpaRepository<EvacuationPlan, Long> {
    List<EvacuationPlan> findByOwnerEmail(String ownerEmail);
    void deleteByOwnerEmail(String ownerEmail); // Delete all plans for a user
}
