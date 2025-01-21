package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.MealPlanRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class MealPlanService {

    private final MealPlanRepo mealPlanRepository;
    private final UserInfoRepo userInfoRepo;

    @Autowired
    public MealPlanService(MealPlanRepo mealPlanRepository, UserInfoRepo userInfoRepo) {
        this.mealPlanRepository = mealPlanRepository;
        this.userInfoRepo = userInfoRepo;
    }

    public MealPlan saveMealPlanWithOwner(MealPlan mealPlan, String ownerEmail) {
        // Fetch the user by email
        UserInfo owner = userInfoRepo.findByUserEmail(ownerEmail)
                .orElseThrow(() -> new IllegalArgumentException("Owner with email " + ownerEmail + " not found"));

        // Check if the user already owns a meal plan
        List<MealPlan> existingPlans = mealPlanRepository.findMealPlansByOwnerEmail(ownerEmail);
        MealPlan existingMealPlan;

        if (!existingPlans.isEmpty()) {
            // Assuming one-to-one relationship, update the first plan
            existingMealPlan = existingPlans.get(0);
        } else {
            // Create a new meal plan if none exists
            existingMealPlan = new MealPlan();
            existingMealPlan.setOwner(owner);
        }

        // Update plan details
        existingMealPlan.setPlanDuration(mealPlan.getPlanDuration());

        if (mealPlan.getMenus() != null) {
            // Clear existing menus and add new ones
            existingMealPlan.getMenus().clear();
            mealPlan.getMenus().forEach(menu -> {
                if (menu.getId() != null) {
                    menu.setId(null); // Detach existing menu IDs
                }
                existingMealPlan.getMenus().add(menu);
            });
        }

        return mealPlanRepository.save(existingMealPlan);
    }

    public MealPlan getOrCreateMealPlanForUser(String ownerEmail) {
        // Fetch the user by email
        UserInfo owner = userInfoRepo.findByUserEmail(ownerEmail)
                .orElseThrow(() -> new IllegalArgumentException("Owner with email " + ownerEmail + " not found"));

        // Check if the user already owns a meal plan
        List<MealPlan> existingPlans = mealPlanRepository.findMealPlansByOwnerEmail(ownerEmail);
        if (!existingPlans.isEmpty()) {
            // Return the existing meal plan
            return existingPlans.get(0);
        }

        // If no plan exists, create a new one
        MealPlan newMealPlan = new MealPlan();
        newMealPlan.setOwner(owner);
        newMealPlan.setPlanDuration(3); // Default plan duration
        newMealPlan.setMenus(new ArrayList<>()); // Start with empty menus

        return mealPlanRepository.save(newMealPlan);
    }


    public List<MealPlan> getMealPlansByOwnerEmail(String email) {
        return mealPlanRepository.findMealPlansByOwnerEmail(email);
    }

    public MealPlan saveMealPlan(MealPlan mealPlan) {
        return mealPlanRepository.save(mealPlan);
    }

    public List<MealPlan> getAllMealPlans() {
        return mealPlanRepository.findAll();
    }

    public Optional<MealPlan> getMealPlanById(Long id) {
        return mealPlanRepository.findById(id);
    }

    public void deleteMealPlan(Long id) {
        if (mealPlanRepository.existsById(id)) {
            mealPlanRepository.deleteById(id);
        } else {
            throw new IllegalArgumentException("Meal Plan with ID " + id + " not found");
        }
    }

    public MealPlan addAdminsToMealPlan(Long mealPlanId, List<String> adminEmails) {
        MealPlan mealPlan = mealPlanRepository.findById(mealPlanId)
                .orElseThrow(() -> new IllegalArgumentException("Meal Plan not found"));

        if (adminEmails == null || adminEmails.isEmpty()) {
            throw new IllegalArgumentException("Admin email list cannot be empty");
        }

        List<UserInfo> admins = userInfoRepo.findByUserEmailIn(adminEmails);
        if (admins.isEmpty()) {
            throw new IllegalArgumentException("No valid users found for the provided emails");
        }

        mealPlan.setAdmins(admins);
        return mealPlanRepository.save(mealPlan);
    }

    public List<MealPlan> getMealPlansByAdminEmail(String email) {
        return mealPlanRepository.findMealPlansByAdminEmail(email);
    }
}
