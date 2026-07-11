package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonBackReference;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "meal_plan_v2") // Renamed table
public class MealPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "meal_plan_data_id", nullable = false)
    @JsonBackReference
    private MealPlanData mealPlanData;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "meal_plan_meals_v2", joinColumns = @JoinColumn(name = "meal_plan_id"))
    @MapKeyColumn(name = "meal_type")
    @Column(name = "meal_name")
    private Map<String, String> meals = new HashMap<>();

    // Per-slot ingredient lists (mealType -> [ingredient, ...]).
    //
    // History: this was originally an @ElementCollection onto a single
    // VARCHAR(255) column, but Map<String, List<String>> is NOT a legal
    // element-collection value type — JPA permits only basic/embeddable map
    // values, never a nested Collection. Hibernate silently failed to
    // round-trip the lists, so on reload every menu came back with an empty
    // ingredients map and FoodPlanCalculatorService (and the shopping list)
    // collapsed to demographic baselines only (Water, pet food). The 2026-07-11
    // hotfix serialized the map by hand to a TEXT column via
    // @PrePersist/@PostLoad + a Jackson ObjectMapper.
    //
    // Native JSONB (2026-07-11 follow-up): that hand-rolled bridge is gone.
    // Hibernate 6 maps the Map directly through @JdbcTypeCode(SqlTypes.JSON) —
    // it serializes/deserializes via the Jackson already on the classpath and,
    // on Postgres, reads/writes a real jsonb column (queryable, indexable),
    // promoted from TEXT by Flyway V40. The field IS the persistent, in-memory
    // and wire (Jackson) shape at once, so getIngredients()/setIngredients()
    // are unchanged and DTOs/services need no edits. `meals`
    // (Map<String,String>) is a legal element collection and is left as it was.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ingredients_json", columnDefinition = "jsonb")
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
        // Preserve the old @PostLoad invariant: never expose null. Hibernate
        // sets the field to null for a SQL NULL jsonb column (e.g. a legacy row
        // that predates this column and hasn't been re-saved), so guard the
        // read. Non-assigning on purpose — the entity uses field access, so
        // touching the field here would dirty it and trigger a spurious UPDATE
        // ('{}' over NULL) on flush.
        return ingredients == null ? new HashMap<>() : ingredients;
    }

    public void setIngredients(Map<String, List<String>> ingredients) {
        this.ingredients = ingredients;
    }
}
