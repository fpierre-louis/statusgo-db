package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.Map;
import java.util.List;

@Entity
public class MealPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ElementCollection
    @CollectionTable(name = "meal_plan_meals", joinColumns = @JoinColumn(name = "meal_plan_id"))
    @MapKeyColumn(name = "meal_type")
    @Column(name = "meal_name")
    private Map<String, String> meals;

    @ElementCollection
    @CollectionTable(name = "meal_plan_ingredients", joinColumns = @JoinColumn(name = "meal_plan_id"))
    @MapKeyColumn(name = "meal_type")
    @Column(name = "ingredients", columnDefinition = "TEXT")
    private Map<String, List<String>> ingredients;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
