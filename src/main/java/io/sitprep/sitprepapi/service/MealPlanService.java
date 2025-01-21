package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.PlanDuration;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.MealPlanRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class MealPlanService {


 private final MealPlanRepo mealPlanRepo;
    private final UserInfoRepo userInfoRepo;

    @Autowired
    public MealPlanService(MealPlanRepo mealPlanRepo, UserInfoRepo userInfoRepo) {
        this.mealPlanRepo = mealPlanRepo;
        this.userInfoRepo = userInfoRepo;
    }

    public MealPlan saveMealPlan(MealPlan mealPlan) {
        return mealPlanRepo.save(mealPlan);
    }


    public MealPlan saveMealPlanWithOwner(MealPlan mealPlan, String ownerEmail) {
        UserInfo owner = userInfoRepo.findByUserEmail(ownerEmail)
                .orElseThrow(() -> new IllegalArgumentException("Owner with email " + ownerEmail + " not found"));

        mealPlan.setOwner(owner);

        if (mealPlan.getPlanDuration() == null) {
            // Set a default plan duration if not provided
            PlanDuration defaultDuration = new PlanDuration();
            defaultDuration.setQuantity(3);
            defaultDuration.setUnit("Days");
            mealPlan.setPlanDuration(defaultDuration);
        }

        return mealPlanRepo.save(mealPlan);
    }

    public MealPlan getOrCreateMealPlanForUser(String ownerEmail) {
        List<MealPlan> mealPlans = mealPlanRepo.findMealPlansByOwnerEmail(ownerEmail);

        if (!mealPlans.isEmpty()) {
            return mealPlans.get(0); // Return the first meal plan for the owner
        }

        // Create a default meal plan if none exists
        MealPlan defaultMealPlan = new MealPlan();
        defaultMealPlan.setNumberOfMenuOptions(3);
        PlanDuration defaultDuration = new PlanDuration();
        defaultDuration.setQuantity(3);
        defaultDuration.setUnit("Days");
        defaultMealPlan.setPlanDuration(defaultDuration);

        return saveMealPlanWithOwner(defaultMealPlan, ownerEmail);
    }

    public Optional<MealPlan> getMealPlanById(Long id) {
        return mealPlanRepo.findById(id);
    }

    public List<MealPlan> getAllMealPlans() {
        return mealPlanRepo.findAll();
    }

    public void deleteMealPlan(Long id) {
        if (mealPlanRepo.existsById(id)) {
            mealPlanRepo.deleteById(id);
        } else {
            throw new IllegalArgumentException("Meal Plan with ID " + id + " not found");
        }
    }

    public MealPlan addAdminsToMealPlan(Long mealPlanId, List<String> adminEmails) {
        MealPlan mealPlan = mealPlanRepo.findById(mealPlanId)
                .orElseThrow(() -> new IllegalArgumentException("Meal Plan not found"));

        List<UserInfo> admins = userInfoRepo.findByUserEmailIn(adminEmails);
        if (admins.isEmpty()) {
            throw new IllegalArgumentException("No valid users found for the provided emails");
        }

        mealPlan.setAdmins(admins);
        return mealPlanRepo.save(mealPlan);
    }

    public List<MealPlan> getMealPlansByAdminEmail(String email) {
        return mealPlanRepo.findMealPlansByAdminEmail(email);
    }
}
