package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class MealPlanDataService {

    private static final Logger logger = LoggerFactory.getLogger(MealPlanDataService.class);

    private final MealPlanDataRepo repository;
    private final HouseholdResolver householdResolver;

    public MealPlanDataService(MealPlanDataRepo repository, HouseholdResolver householdResolver) {
        this.repository = repository;
        this.householdResolver = householdResolver;
    }

    public java.util.List<MealPlanData> getAllMealPlans() {
        logger.info("Fetching all meal plans");
        return repository.findAll();
    }

    public Optional<MealPlanData> findByOwnerEmailCI(String ownerEmail) {
        return repository.findFirstByOwnerEmailIgnoreCase(norm(ownerEmail));
    }

    /**
     * Create or update the meal plan for a household (one per household).
     * Cross-household edit (X-Household-Id, admin of that household) targets
     * that household; else the author's base. Resolves the existing row by
     * householdId — falling back to the legacy by-owner row when householdId
     * isn't set yet — so the base path stays unambiguous now that ownerEmail
     * is no longer unique.
     */
    @Transactional
    public MealPlanData upsert(MealPlanData incoming) {
        final String email = norm(incoming.getOwnerEmail());
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("ownerEmail is required");
        }

        String targetHh = householdResolver.writableTargetHousehold(email);
        String hh = targetHh != null ? targetHh : householdResolver.baseHouseholdIdFor(email);

        MealPlanData existing = null;
        if (hh != null) existing = repository.findFirstByHouseholdId(hh).orElse(null);
        if (existing == null) existing = repository.findFirstByOwnerEmailIgnoreCase(email).orElse(null);

        if (existing != null) {
            // Update existing (keep id + original author).
            existing.setPlanDuration(incoming.getPlanDuration());
            existing.setNumberOfMenuOptions(incoming.getNumberOfMenuOptions());
            existing.setSelectedItemsJson(incoming.getSelectedItemsJson());
            // Only move the gather-confirmation flag when the caller actually
            // sent it (nullable) — an ordinary shopping-list save omits it and
            // must not reset a household that already confirmed it's stocked.
            if (incoming.getSuppliesGathered() != null) {
                existing.setSuppliesGathered(incoming.getSuppliesGathered());
            }
            if (hh != null) existing.setHouseholdId(hh);

            // Replace child collection safely (orphanRemoval=true, cascade=ALL).
            existing.getMealPlan().clear();
            if (incoming.getMealPlan() != null) {
                for (MealPlan mp : incoming.getMealPlan()) {
                    mp.setId(null);                // force insert
                    mp.setMealPlanData(existing);  // set parent
                    existing.getMealPlan().add(mp);
                }
            }
            return repository.save(existing);
        }

        // New record. Force a clean INSERT: MealPlanData.id is DB IDENTITY-
        // generated, but the client can send a synthetic/stale id (the FE seeds
        // mealPlanData.id = Date.now() for its local cache, and a rebuilt local
        // DB leaves that id pointing at no row). A non-null id makes Spring
        // Data's save() run merge() on a detached, non-existent row →
        // Hibernate StaleObjectStateException → OptimisticLockingFailureException
        // → 409. Nulling it guarantees persist() and a server-owned id.
        incoming.setId(null);
        incoming.setOwnerEmail(email);
        incoming.setHouseholdId(hh);
        if (incoming.getMealPlan() != null) {
            for (MealPlan mp : incoming.getMealPlan()) {
                mp.setId(null);
                mp.setMealPlanData(incoming);
            }
        }
        return repository.save(incoming);
    }

    /** Update by route ownerEmail; throw to signal 404 to controller */
    @Transactional
    public MealPlanData updateByOwnerEmail(String ownerEmail, MealPlanData payload) {
        String email = norm(ownerEmail);
        MealPlanData existing = repository.findFirstByOwnerEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("Meal plan not found for: " + email));

        existing.setPlanDuration(payload.getPlanDuration());
        existing.setNumberOfMenuOptions(payload.getNumberOfMenuOptions());
        existing.setSelectedItemsJson(payload.getSelectedItemsJson());
        if (payload.getSuppliesGathered() != null) {
            existing.setSuppliesGathered(payload.getSuppliesGathered());
        }
        if (existing.getHouseholdId() == null) {
            existing.setHouseholdId(householdResolver.baseHouseholdIdFor(email));
        }

        existing.getMealPlan().clear();
        if (payload.getMealPlan() != null) {
            for (MealPlan mp : payload.getMealPlan()) {
                mp.setId(null);
                mp.setMealPlanData(existing);
                existing.getMealPlan().add(mp);
            }
        }
        return repository.save(existing);
    }

    @Transactional
    public void deleteByOwnerEmail(String ownerEmail) {
        String email = norm(ownerEmail);
        repository.findFirstByOwnerEmailIgnoreCase(email).ifPresent(repository::delete);
    }

    private static String norm(String s) {
        return s == null ? null : s.trim().toLowerCase();
    }
}
