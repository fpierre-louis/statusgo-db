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

    @Transactional
    public List<EvacuationPlan> saveAllEvacuationPlans(String ownerEmail, List<EvacuationPlan> evacuationPlans) {
        if (ownerEmail == null || ownerEmail.isEmpty()) {
            throw new IllegalArgumentException("Owner email cannot be null or empty");
        }

        evacuationPlanRepo.deleteByOwnerEmail(ownerEmail);
        evacuationPlans.forEach(plan -> plan.setOwnerEmail(ownerEmail));

        return evacuationPlanRepo.saveAll(evacuationPlans);
    }


    public List<EvacuationPlan> getEvacuationPlansByOwner(String ownerEmail) {
        return evacuationPlanRepo.findByOwnerEmail(ownerEmail);
    }
}
