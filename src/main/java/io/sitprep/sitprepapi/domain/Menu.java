package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Menu {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String breakfast;
    private String lunch;
    private String dinner;
    private String snack;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> breakfastIngredients = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> lunchIngredients = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> dinnerIngredients = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> snackIngredients = new ArrayList<>();

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBreakfast() {
        return breakfast;
    }

    public void setBreakfast(String breakfast) {
        this.breakfast = breakfast;
    }

    public String getLunch() {
        return lunch;
    }

    public void setLunch(String lunch) {
        this.lunch = lunch;
    }

    public String getDinner() {
        return dinner;
    }

    public void setDinner(String dinner) {
        this.dinner = dinner;
    }

    public String getSnack() {
        return snack;
    }

    public void setSnack(String snack) {
        this.snack = snack;
    }

    public List<String> getBreakfastIngredients() {
        return breakfastIngredients;
    }

    public void setBreakfastIngredients(List<String> breakfastIngredients) {
        this.breakfastIngredients = breakfastIngredients;
    }

    public List<String> getLunchIngredients() {
        return lunchIngredients;
    }

    public void setLunchIngredients(List<String> lunchIngredients) {
        this.lunchIngredients = lunchIngredients;
    }

    public List<String> getDinnerIngredients() {
        return dinnerIngredients;
    }

    public void setDinnerIngredients(List<String> dinnerIngredients) {
        this.dinnerIngredients = dinnerIngredients;
    }

    public List<String> getSnackIngredients() {
        return snackIngredients;
    }

    public void setSnackIngredients(List<String> snackIngredients) {
        this.snackIngredients = snackIngredients;
    }
}
