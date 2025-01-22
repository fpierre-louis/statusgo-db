package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.List;
import java.util.Map;

@Entity
public class MealPlanData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ElementCollection
    private List<MealPlan> mealPlan;

    private int numberOfMenuOptions;

    @Embedded
    private PlanDuration planDuration;

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public List<MealPlan> getMealPlan() {
        return mealPlan;
    }

    public void setMealPlan(List<MealPlan> mealPlan) {
        this.mealPlan = mealPlan;
    }

    public int getNumberOfMenuOptions() {
        return numberOfMenuOptions;
    }

    public void setNumberOfMenuOptions(int numberOfMenuOptions) {
        this.numberOfMenuOptions = numberOfMenuOptions;
    }

    public PlanDuration getPlanDuration() {
        return planDuration;
    }

    public void setPlanDuration(PlanDuration planDuration) {
        this.planDuration = planDuration;
    }
}

@Embeddable
class PlanDuration {
    private int quantity;
    private String unit;

    // Getters and Setters

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}

@Embeddable
class MealPlan {
    @ElementCollection
    private Map<String, String> meals; // breakfast, lunch, dinner, snack

    @ElementCollection
    private Map<String, List<String>> ingredients;

    // Getters and Setters

    public Map<String, String> getMeals() {
        return meals;
    }

    public void setMeals(Map<String, String> meals) {
        this.meals = meals;
    }

    public Map<String, List<String>> getIngredients() {
        return ingredients;
    }

    public void setIngredients(Map<String, List<String>> ingredients) {
        this.ingredients = ingredients;
    }
}

