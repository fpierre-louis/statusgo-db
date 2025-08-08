package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class EvacuationPlanService {

    private final EvacuationPlanRepo evacuationPlanRepo;

    public EvacuationPlanService(EvacuationPlanRepo evacuationPlanRepo) {
        this.evacuationPlanRepo = evacuationPlanRepo;
    }

    @Transactional
    public List<EvacuationPlan> saveAllEvacuationPlans(String ownerEmail, List<EvacuationPlan> evacuationPlans) {
        // Delete all existing plans for the user to prevent duplicates
        evacuationPlanRepo.deleteByOwnerEmail(ownerEmail);

        // Ensure each plan is assigned to the correct owner
        evacuationPlans.forEach(plan -> plan.setOwnerEmail(ownerEmail));

        // Save the new list of plans
        return evacuationPlanRepo.saveAll(evacuationPlans);
    }

    public List<EvacuationPlan> getEvacuationPlansForCurrentUser() {
        String currentUserEmail = AuthUtils.getCurrentUserEmail();
        return evacuationPlanRepo.findByOwnerEmail(currentUserEmail);
    }

    public List<EvacuationPlan> getEvacuationPlansByOwner(String ownerEmail) {
        return evacuationPlanRepo.findByOwnerEmail(ownerEmail);
    }
}