package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.MealPlanRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
        UserInfo owner = userInfoRepo.findByUserEmail(ownerEmail)
                .orElseThrow(() -> new IllegalArgumentException("Owner with email " + ownerEmail + " not found"));

        mealPlan.setOwner(owner);

        // Ensure menus are properly initialized
        if (mealPlan.getMenus() != null) {
            mealPlan.getMenus().forEach(menu -> {
                if (menu.getId() != null) {
                    // Detach menu to avoid conflicts if it already exists
                    menu.setId(null);
                }
            });
        }

        return mealPlanRepository.save(mealPlan);
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
