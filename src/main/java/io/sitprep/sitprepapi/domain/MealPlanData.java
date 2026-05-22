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

    @Column(nullable = false, unique = true)
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
