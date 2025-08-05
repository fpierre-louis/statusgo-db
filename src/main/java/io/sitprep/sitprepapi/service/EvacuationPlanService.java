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
    public List<EvacuationPlan> saveAllEvacuationPlans(List<EvacuationPlan> evacuationPlans) {
        String currentUserEmail = AuthUtils.getCurrentUserEmail();

        evacuationPlanRepo.deleteByOwnerEmail(currentUserEmail);
        evacuationPlans.forEach(plan -> plan.setOwnerEmail(currentUserEmail));

        return evacuationPlanRepo.saveAll(evacuationPlans);
    }

    public List<EvacuationPlan> getEvacuationPlansForCurrentUser() {
        String currentUserEmail = AuthUtils.getCurrentUserEmail();
        return evacuationPlanRepo.findByOwnerEmail(currentUserEmail);
    }

    public List<EvacuationPlan> saveAllEvacuationPlans(String ownerEmail, List<EvacuationPlan> plans) {
        evacuationPlanRepo.deleteAll(evacuationPlanRepo.findByOwnerEmail(ownerEmail));
        plans.forEach(plan -> plan.setOwnerEmail(ownerEmail));
        return evacuationPlanRepo.saveAll(plans);
    }

    public List<EvacuationPlan> getEvacuationPlansByOwner(String ownerEmail) {
        return evacuationPlanRepo.findByOwnerEmail(ownerEmail);
    }

}
