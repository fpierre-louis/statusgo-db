package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "meal_plan_data_v2") // Renamed table
public class MealPlanData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // No longer UNIQUE: meal plans are household-keyed, so one author may own
    // several (their base + any household they cross-household-edit). The DB
    // constraint is dropped at boot by MealPlanOwnerUniqueDropRunner.
    @Column(nullable = false)
    private String ownerEmail;

    // Owning household (Group.groupId, groupType="Household"). Nullable
    // during the ownerEmail->household migration; backfilled on boot.
    private String householdId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "mealPlanData", fetch = FetchType.EAGER)
    @JsonManagedReference
    private List<MealPlan> mealPlan = new ArrayList<>();

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "quantity", column = @Column(name = "plan_duration_quantity")),
            @AttributeOverride(name = "unit", column = @Column(name = "plan_duration_unit"))
    })
    private PlanDuration planDuration;

    private int numberOfMenuOptions;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "selected_items_json", columnDefinition = "TEXT")
    private String selectedItemsJson;

    // Three-state Food Planner completion (2026-07-11). A plan can exist
    // (menus + duration + shopping list) yet the household hasn't actually
    // ACQUIRED the food. This flag captures the user's explicit "I've gathered
    // these supplies" confirmation from the end of the shopping-list step, so
    // the dashboard can show: no plan (grey) -> plan built / not gathered
    // (yellow) -> stocked (green). Household-scoped like the rest of the row.
    //
    // Nullable on purpose: an ordinary "Save shopping list" omits the field, so
    // upsert leaves the prior value alone (null != a real false). Only the
    // explicit gather-confirmation toggle sends true/false. Treated as false
    // when null everywhere it's read.
    @Column(name = "supplies_gathered")
    private Boolean suppliesGathered;

    public void setId(Long id) {
        this.id = id;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    public void setHouseholdId(String householdId) {
        this.householdId = householdId;
    }

    public void setMealPlan(List<MealPlan> mealPlan) {
        this.mealPlan.clear();
        if (mealPlan != null) {
            for (MealPlan meal : mealPlan) {
                addMealPlan(meal);
            }
        }
    }

    public void addMealPlan(MealPlan meal) {
        if (!this.mealPlan.contains(meal)) {
            meal.setMealPlanData(this);
            this.mealPlan.add(meal);
        }
    }

    public void removeMealPlan(MealPlan meal) {
        this.mealPlan.remove(meal);
        meal.setMealPlanData(null);
    }

    public void setPlanDuration(PlanDuration planDuration) {
        this.planDuration = planDuration;
    }

    public void setNumberOfMenuOptions(int numberOfMenuOptions) {
        this.numberOfMenuOptions = numberOfMenuOptions;
    }

    public void setSelectedItemsJson(String selectedItemsJson) {
        this.selectedItemsJson = selectedItemsJson;
    }

    public void setSuppliesGathered(Boolean suppliesGathered) {
        this.suppliesGathered = suppliesGathered;
    }

    @Override
    public String toString() {
        return "MealPlanData{" +
                "id=" + id +
                ", ownerEmail='" + ownerEmail + '\'' +
                ", numberOfMenuOptions=" + numberOfMenuOptions +
                ", planDuration=" + (planDuration != null ? planDuration.toString() : "null") +
                '}';
    }
}
