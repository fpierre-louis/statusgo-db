package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import lombok.Getter;

@Getter
@Entity
public class MealPlanData {

    // Getters and Setters
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String ownerEmail;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "mealPlanData", fetch = FetchType.LAZY)
    @JsonManagedReference // Manages bidirectional reference to MealPlan
    private List<MealPlan> mealPlan = new ArrayList<>();

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "quantity", column = @Column(name = "plan_duration_quantity")),
            @AttributeOverride(name = "unit", column = @Column(name = "plan_duration_unit"))
    })
    private PlanDuration planDuration;

    private int numberOfMenuOptions;

    public void setId(Long id) {
        this.id = id;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
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
            meal.setMealPlanData(this); // Ensure child references parent
            this.mealPlan.add(meal);
        }
    }

    public void removeMealPlan(MealPlan meal) {
        this.mealPlan.remove(meal);
        meal.setMealPlanData(null); // Prevent orphan references
    }

    public void setPlanDuration(PlanDuration planDuration) {
        this.planDuration = planDuration;
    }

    public void setNumberOfMenuOptions(int numberOfMenuOptions) {
        this.numberOfMenuOptions = numberOfMenuOptions;
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
