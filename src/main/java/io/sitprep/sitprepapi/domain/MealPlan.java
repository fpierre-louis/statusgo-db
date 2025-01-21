package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
@Entity
public class MealPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    private PlanDuration planDuration;

    @Column(nullable = false) // Ensures the column is always set
    private int numberOfMenuOptions = 3; // Default value

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Menu> menus = new ArrayList<>();

    @ManyToMany
    @JoinTable(
            name = "meal_plan_admins",
            joinColumns = @JoinColumn(name = "meal_plan_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    private List<UserInfo> admins = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "owner_id", nullable = false, unique = true)
    private UserInfo owner;

    // Getters and Setters
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

    public List<UserInfo> getAdmins() {
        return admins;
    }

    public void setAdmins(List<UserInfo> admins) {
        this.admins = admins;
    }

    public UserInfo getOwner() {
        return owner;
    }

    public void setOwner(UserInfo owner) {
        this.owner = owner;
    }
}
