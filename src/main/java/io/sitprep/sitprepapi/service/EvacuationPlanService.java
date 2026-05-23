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
    private final HouseholdResolver householdResolver;

    public EvacuationPlanService(EvacuationPlanRepo evacuationPlanRepo,
                                 HouseholdResolver householdResolver) {
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.householdResolver = householdResolver;
    }

    @Transactional
    public List<EvacuationPlan> saveAllEvacuationPlans(String ownerEmail, List<EvacuationPlan> evacuationPlans) {
        // Cross-household edit (X-Household-Id, admin of that household):
        // replace THAT household's evacuation plans + stamp it. Else unchanged.
        String target = householdResolver.writableTargetHousehold(ownerEmail);
        if (target != null) {
            evacuationPlanRepo.deleteAll(evacuationPlanRepo.findByHouseholdId(target));
            evacuationPlans.forEach(plan -> {
                plan.setOwnerEmail(ownerEmail);
                plan.setHouseholdId(target);
            });
            return evacuationPlanRepo.saveAll(evacuationPlans);
        }

        // Delete all existing plans for the user to prevent duplicates
        evacuationPlanRepo.deleteByOwnerEmail(ownerEmail);

        // Ensure each plan is assigned to the correct owner + household
        String householdId = householdResolver.baseHouseholdIdFor(ownerEmail);
        evacuationPlans.forEach(plan -> {
            plan.setOwnerEmail(ownerEmail);
            if (plan.getHouseholdId() == null) plan.setHouseholdId(householdId);
        });

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

    /**
     * Create a single evacuation plan WITHOUT deleting the owner's
     * existing ones (unlike the bulk save). Used by the activation
     * surface's "add another shelter".
     */
    public EvacuationPlan addEvacuationPlan(EvacuationPlan plan) {
        if (plan.getHouseholdId() == null) {
            String target = householdResolver.writableTargetHousehold(plan.getOwnerEmail());
            plan.setHouseholdId(target != null
                    ? target
                    : householdResolver.baseHouseholdIdFor(plan.getOwnerEmail()));
        }
        return evacuationPlanRepo.save(plan);
    }
}