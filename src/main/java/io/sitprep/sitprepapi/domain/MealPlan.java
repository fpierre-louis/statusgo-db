package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.List;
import java.util.Map;

@Entity
public class MealPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ElementCollection
    @CollectionTable(name = "meal_items", joinColumns = @JoinColumn(name = "meal_plan_id"))
    @MapKeyColumn(name = "meal_type") // Key column for map (e.g., breakfast, lunch, etc.)
    @Column(name = "meal_name") // Value column for map
    private Map<String, String> meals;

    @ElementCollection
    @CollectionTable(name = "meal_ingredients", joinColumns = @JoinColumn(name = "meal_plan_id"))
    @Column(name = "ingredient")
    private List<String> ingredients; // Simplified as a list for easier mapping

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

    public List<String> getIngredients() {
        return ingredients;
    }

    public void setIngredients(List<String> ingredients) {
        this.ingredients = ingredients;
    }
}
