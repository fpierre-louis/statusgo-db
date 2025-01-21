package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Transient
    private Map<String, List<String>> ingredients = new HashMap<>();

    @PostLoad
    public void populateIngredients() {
        ingredients.put("breakfast", mapMealToIngredients(breakfast));
        ingredients.put("lunch", mapMealToIngredients(lunch));
        ingredients.put("dinner", mapMealToIngredients(dinner));
        ingredients.put("snack", mapMealToIngredients(snack));
    }

    private List<String> mapMealToIngredients(String meal) {
        Map<String, List<String>> mealMapping = new HashMap<>();
        mealMapping.put("Cereal with Milk and Fruit", List.of("Cereal", "Milk", "Fruit", "Juice"));
        mealMapping.put("Cereal with Milk, Fruit, and Nuts", List.of("Cereal", "Milk", "Fruit", "Nuts", "Juice"));
        mealMapping.put("Cereal with Milk, Fruit, and Granola Bar", List.of("Cereal", "Milk", "Fruit", "Granola Bar", "Juice"));
        mealMapping.put("Cereal with Milk, Fruit, and Peanut Butter", List.of("Cereal", "Milk", "Fruit", "Peanut Butter", "Juice"));
        mealMapping.put("Tuna and Crackers Combo", List.of("Tuna", "Crackers", "Fruit", "Veggies", "Juice"));
        mealMapping.put("Chilli and Crackers", List.of("Chili", "Crackers", "Fruit", "Veggies", "Juice"));
        mealMapping.put("Beef Stew and Crackers Combo", List.of("Beef Stew", "Crackers", "Fruit", "Veggies", "Juice"));
        mealMapping.put("Tofu with Veggies and Crackers", List.of("Tofu", "Veggies", "Crackers", "Juice"));
        mealMapping.put("Ravioli with Fruits and Crackers", List.of("Ravioli", "Fruit", "Crackers", "Juice"));
        mealMapping.put("Granola Bar", List.of("Granola Bar"));
        mealMapping.put("Nuts or Dried Fruits", List.of("Nuts", "Dried Fruits"));
        mealMapping.put("Crackers", List.of("Crackers"));
        mealMapping.put("Peanut Butter and Crackers", List.of("Peanut Butter", "Crackers"));

        return mealMapping.getOrDefault(meal, List.of());
    }

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

    public Map<String, List<String>> getIngredients() {
        return ingredients;
    }
}
