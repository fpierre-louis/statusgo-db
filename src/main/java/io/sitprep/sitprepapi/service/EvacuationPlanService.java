package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class EvacuationPlanService {

    private final EvacuationPlanRepo evacuationPlanRepo;

    public EvacuationPlanService(EvacuationPlanRepo evacuationPlanRepo) {
        this.evacuationPlanRepo = evacuationPlanRepo;
    }

    @Transactional // Ensures atomic save operation
    public List<EvacuationPlan> saveAllEvacuationPlans(String ownerEmail, List<EvacuationPlan> evacuationPlans) {
        // Validate input
        if (ownerEmail == null || ownerEmail.isEmpty()) {
            throw new IllegalArgumentException("Owner email cannot be null or empty");
        }

        // Remove existing plans for this user before saving
        evacuationPlanRepo.deleteByOwnerEmail(ownerEmail);

        // Assign ownerEmail before saving to ensure consistency
        evacuationPlans.forEach(plan -> plan.setOwnerEmail(ownerEmail));

        // Save new plans
        return evacuationPlanRepo.saveAll(evacuationPlans);
    }

    public List<EvacuationPlan> getEvacuationPlansByOwner(String ownerEmail) {
        return evacuationPlanRepo.findByOwnerEmail(ownerEmail);
    }
}
