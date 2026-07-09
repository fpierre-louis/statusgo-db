package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.GeoUtil;
import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.dto.RouteNotesDto;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class EvacuationPlanService {

    private final EvacuationPlanRepo evacuationPlanRepo;
    private final HouseholdResolver householdResolver;
    private final ActivationPlanUpdateBroadcastService activationPlanUpdates;

    public EvacuationPlanService(EvacuationPlanRepo evacuationPlanRepo,
                                 HouseholdResolver householdResolver,
                                 ActivationPlanUpdateBroadcastService activationPlanUpdates) {
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.householdResolver = householdResolver;
        this.activationPlanUpdates = activationPlanUpdates;
    }

    @Transactional
    public List<EvacuationPlan> saveAllEvacuationPlans(String ownerEmail, List<EvacuationPlan> evacuationPlans) {
        evacuationPlans.forEach(p -> GeoUtil.requireValidLatLng(p.getLat(), p.getLng()));
        // Cross-household edit (X-Household-Id, admin of that household):
        // replace THAT household's evacuation plans + stamp it. Else unchanged.
        String target = householdResolver.writableTargetHousehold(ownerEmail);
        if (target != null) {
            evacuationPlanRepo.deleteAll(evacuationPlanRepo.findByHouseholdId(target));
            evacuationPlans.forEach(plan -> {
                plan.setOwnerEmail(ownerEmail);
                plan.setHouseholdId(target);
            });
            List<EvacuationPlan> saved = evacuationPlanRepo.saveAll(evacuationPlans);
            activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(ownerEmail, "evacuationPlans");
            return saved;
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
        List<EvacuationPlan> saved = evacuationPlanRepo.saveAll(evacuationPlans);
        activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(ownerEmail, "evacuationPlans");
        return saved;
    }

    public List<EvacuationPlan> getEvacuationPlansForCurrentUser() {
        String currentUserEmail = AuthUtils.getCurrentUserEmail();
        return evacuationPlanRepo.findByOwnerEmail(currentUserEmail);
    }

    public List<EvacuationPlan> getEvacuationPlansByOwner(String ownerEmail) {
        return evacuationPlanRepo.findByOwnerEmail(ownerEmail);
    }

    /**
     * NON-DESTRUCTIVE route-notes update (SYSTEM_TRAPS T-17). Loads the
     * household's EXISTING evacuation plans and updates ONLY the route fields
     * (primary/alternate route notes, offline-map flag, and — when provided —
     * last-practiced), then {@code saveAll()} which issues an UPDATE per row
     * (the ids exist). It NEVER deletes, so — unlike {@link #saveAllEvacuationPlans}
     * — a wizard "Routes &amp; maps" save cannot wipe destinations, shelters, or
     * coordinates, even if the client sends only the route fields (or its prior
     * read failed). If the household has no plan yet, one minimal route-only plan
     * is created. Mirrors the household resolution of the bulk save.
     */
    @Transactional
    public List<EvacuationPlan> updateRouteNotes(String ownerEmail, RouteNotesDto notes) {
        String target = householdResolver.writableTargetHousehold(ownerEmail);
        List<EvacuationPlan> plans = target != null
                ? evacuationPlanRepo.findByHouseholdId(target)
                : evacuationPlanRepo.findByOwnerEmail(ownerEmail);

        if (plans.isEmpty()) {
            EvacuationPlan p = new EvacuationPlan();
            p.setOwnerEmail(ownerEmail);
            p.setName("Evacuation Plan A");
            p.setHouseholdId(target != null ? target : householdResolver.baseHouseholdIdFor(ownerEmail));
            applyRouteNotes(p, notes);
            List<EvacuationPlan> saved = List.of(evacuationPlanRepo.save(p));
            activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(ownerEmail, "evacuationPlans");
            return saved;
        }

        plans.forEach(p -> applyRouteNotes(p, notes));
        List<EvacuationPlan> saved = evacuationPlanRepo.saveAll(plans); // UPDATE by id — everything else preserved
        activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(ownerEmail, "evacuationPlans");
        return saved;
    }

    private static void applyRouteNotes(EvacuationPlan p, RouteNotesDto notes) {
        p.setPrimaryRouteNotes(notes.primaryRouteNotes());
        p.setAlternateRouteNotes(notes.alternateRouteNotes());
        p.setOfflineMapSaved(notes.offlineMapSaved());
        // Only overwrite last-practiced when the caller sends it (the wizard doesn't),
        // so a routes save never clobbers a future drill-hook timestamp.
        if (notes.lastPracticedAt() != null) p.setLastPracticedAt(notes.lastPracticedAt());
    }

    /**
     * Create a single evacuation plan WITHOUT deleting the owner's
     * existing ones (unlike the bulk save). Used by the activation
     * surface's "add another shelter".
     */
    @Transactional
    public EvacuationPlan addEvacuationPlan(EvacuationPlan plan) {
        GeoUtil.requireValidLatLng(plan.getLat(), plan.getLng());
        if (plan.getHouseholdId() == null) {
            String target = householdResolver.writableTargetHousehold(plan.getOwnerEmail());
            plan.setHouseholdId(target != null
                    ? target
                    : householdResolver.baseHouseholdIdFor(plan.getOwnerEmail()));
        }
        EvacuationPlan saved = evacuationPlanRepo.save(plan);
        activationPlanUpdates.broadcastOwnerPlanChangedAfterCommit(plan.getOwnerEmail(), "evacuationPlans");
        return saved;
    }
}
