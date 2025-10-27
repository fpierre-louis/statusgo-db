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

    public MealPlanDataService(MealPlanDataRepo repository) {
        this.repository = repository;
    }

    public java.util.List<MealPlanData> getAllMealPlans() {
        logger.info("Fetching all meal plans");
        return repository.findAll();
    }

    public Optional<MealPlanData> findByOwnerEmailCI(String ownerEmail) {
        return repository.findFirstByOwnerEmailIgnoreCase(norm(ownerEmail));
    }

    /** Create or update (idempotent) by ownerEmail present in the payload. */
    @Transactional
    public MealPlanData upsert(MealPlanData incoming) {
        final String email = norm(incoming.getOwnerEmail());
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("ownerEmail is required");
        }

        Optional<MealPlanData> existingOpt = repository.findFirstByOwnerEmailIgnoreCase(email);
        if (existingOpt.isPresent()) {
            // Update existing (keep id)
            MealPlanData existing = existingOpt.get();
            existing.setPlanDuration(incoming.getPlanDuration());
            existing.setNumberOfMenuOptions(incoming.getNumberOfMenuOptions());

            // Replace child collection safely (requires orphanRemoval=true, cascade=ALL on entity)
            existing.getMealPlan().clear();
            if (incoming.getMealPlan() != null) {
                for (MealPlan mp : incoming.getMealPlan()) {
                    mp.setId(null);                // force insert
                    mp.setMealPlanData(existing);  // set parent
                    existing.getMealPlan().add(mp);
                }
            }
            return repository.save(existing);
        } else {
            // New record
            incoming.setOwnerEmail(email);
            if (incoming.getMealPlan() != null) {
                for (MealPlan mp : incoming.getMealPlan()) {
                    mp.setId(null);
                    mp.setMealPlanData(incoming);
                }
            }
            return repository.save(incoming);
        }
    }

    /** Update by route ownerEmail; throw to signal 404 to controller */
    @Transactional
    public MealPlanData updateByOwnerEmail(String ownerEmail, MealPlanData payload) {
        String email = norm(ownerEmail);
        MealPlanData existing = repository.findFirstByOwnerEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("Meal plan not found for: " + email));

        existing.setPlanDuration(payload.getPlanDuration());
        existing.setNumberOfMenuOptions(payload.getNumberOfMenuOptions());

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
