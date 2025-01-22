package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.List;

@Entity
public class MealPlanData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ownerEmail; // New field for user identification

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "meal_plan_data_id") // Foreign key in MealPlan table
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

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
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
