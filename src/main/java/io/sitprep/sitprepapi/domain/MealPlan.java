package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonBackReference;

@Entity
public class MealPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "meal_plan_data_id", nullable = false)
    @JsonBackReference  // Prevent infinite recursion when serializing
    private MealPlanData mealPlanData;

    @ElementCollection
    @CollectionTable(name = "meal_plan_meals", joinColumns = @JoinColumn(name = "meal_plan_id"))
    @MapKeyColumn(name = "meal_type")
    @Column(name = "meal_name")
    private Map<String, String> meals = new HashMap<>();

    @ElementCollection
    @CollectionTable(name = "meal_plan_ingredients", joinColumns = @JoinColumn(name = "meal_plan_id"))
    @MapKeyColumn(name = "meal_type")
    @Column(name = "ingredients")
    private Map<String, List<String>> ingredients = new HashMap<>();

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public MealPlanData getMealPlanData() {
        return mealPlanData;
    }

    public void setMealPlanData(MealPlanData mealPlanData) {
        this.mealPlanData = mealPlanData;
    }

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
