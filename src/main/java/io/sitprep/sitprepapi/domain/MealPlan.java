package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.List;

@Entity
public class MealPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    private PlanDuration planDuration;

    @Column(nullable = false)
    private int numberOfMenuOptions = 3;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Menu> menus;

    @Column(nullable = false)
    private String ownerEmail;

    // Standard getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PlanDuration getPlanDuration() {
        return planDuration;
    }

    public void setPlanDuration(PlanDuration planDuration) {
        this.planDuration = planDuration;
    }

    public int getNumberOfMenuOptions() {
        return numberOfMenuOptions;
    }

    public void setNumberOfMenuOptions(int numberOfMenuOptions) {
        this.numberOfMenuOptions = numberOfMenuOptions;
    }

    public List<Menu> getMenus() {
        return menus;
    }

    public void setMenus(List<Menu> menus) {
        this.menus = menus;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }
}
