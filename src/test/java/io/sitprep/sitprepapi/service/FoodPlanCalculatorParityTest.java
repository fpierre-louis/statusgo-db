package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.domain.PlanDuration;
import io.sitprep.sitprepapi.dto.FoodPlanDtos.FoodPlanItemDto;
import io.sitprep.sitprepapi.dto.FoodPlanDtos.FoodPlanRecommendationDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Baseline parity: 2 adults + 2 kids, 3-day plan, default menu (Cereal+Milk+
 * Fruit / Tuna+Crackers / Beef Stew+Crackers / Granola Bar). Expected raw
 * totals + package counts computed by hand from the OLD client
 * useCalculatedList logic, so this asserts the Java port matches (and, for
 * Cereal, uses the canonical 18 oz package — resolving the 18-vs-8 drift).
 */
@SpringBootTest
@ActiveProfiles("test")
class FoodPlanCalculatorParityTest {

    @Autowired FoodPlanCalculatorService service;
    @Autowired DemographicRepo demographicRepo;
    @Autowired MealPlanDataRepo mealPlanDataRepo;

    @Test
    void baseline_2adults_2kids_3days() {
        String hh = "hh-food-" + UUID.randomUUID();

        Demographic d = new Demographic();
        d.setHouseholdId(hh);
        d.setOwnerEmail("owner@x.com");
        d.setAdults(2);
        d.setKids(2);
        demographicRepo.save(d);

        MealPlan menu = new MealPlan();
        menu.setMeals(Map.of(
                "breakfast", "Cereal with Milk and Fruit",
                "lunch", "Tuna and Crackers Combo",
                "dinner", "Beef Stew and Crackers Combo",
                "snack", "Granola Bar"));
        menu.setIngredients(Map.of(
                "breakfast", List.of("Cereal", "Milk", "Fruit", "Juice"),
                "lunch", List.of("Tuna", "Crackers", "Fruit", "Veggies", "Juice"),
                "dinner", List.of("Beef Stew", "Crackers", "Fruit", "Veggies", "Juice"),
                "snack", List.of("Granola Bar")));

        MealPlanData mpd = new MealPlanData();
        mpd.setHouseholdId(hh);
        mpd.setOwnerEmail("owner@x.com");
        PlanDuration pd = new PlanDuration();
        pd.setQuantity(3);
        pd.setUnit("Days");
        mpd.setPlanDuration(pd);
        mpd.setMealPlan(List.of(menu));
        mealPlanDataRepo.save(mpd);

        FoodPlanRecommendationDto plan = service.recommendForHousehold(hh);

        assertThat(plan.planDays()).isEqualTo(3);
        assertThat(plan.demographic().persons()).isEqualTo(4);

        // Water is priority 1 → first.
        assertThat(plan.items().get(0).item()).isEqualTo("Water");

        Map<String, FoodPlanItemDto> byItem = plan.items().stream()
                .collect(java.util.stream.Collectors.toMap(FoodPlanItemDto::item, i -> i));

        // Raw totals (2 adults + 2 kids, 3 days, 1 menu):
        assertRaw(byItem, "Water", 12.0);       // (2*1 + 2*1) * 3
        assertRaw(byItem, "Cereal", 72.0);       // (2*8 + 2*4) * 3, 1x/day
        assertRaw(byItem, "Milk", 72.0);
        assertRaw(byItem, "Fruit", 108.0);       // 12/occurrence * 3 occ/day * 3 days
        assertRaw(byItem, "Juice", 216.0);       // 24/occ * 3 occ * 3 days
        assertRaw(byItem, "Tuna", 45.0);         // 15 * 3
        assertRaw(byItem, "Crackers", 108.0);    // 18/occ * 2 occ * 3
        assertRaw(byItem, "Veggies", 72.0);      // 12/occ * 2 occ * 3
        assertRaw(byItem, "Beef Stew", 72.0);
        assertRaw(byItem, "Granola Bar", 9.0);   // 3 * 3

        // Package counts (canonical catalog sizes):
        assertThat(byItem.get("Water").packagesNeeded()).isEqualTo(3);      // 12 gal→1536 fl oz / 678.4
        assertThat(byItem.get("Cereal").packagesNeeded()).isEqualTo(4);     // 72 oz / 18  (NOT 8 — drift fixed)
        assertThat(byItem.get("Juice").packagesNeeded()).isEqualTo(4);      // 216 / 64
        assertThat(byItem.get("Granola Bar").packagesNeeded()).isEqualTo(1);// 9 bars / 12
        assertThat(byItem.get("Tuna").packagesNeeded()).isEqualTo(9);       // 45 / 5

        // No infants / pets → those items absent.
        assertThat(byItem).doesNotContainKeys("Baby Food", "Baby Formula", "Worth of dog food");
        assertThat(plan.items()).hasSize(10);
    }

    private static void assertRaw(Map<String, FoodPlanItemDto> byItem, String item, double expected) {
        assertThat(byItem).containsKey(item);
        assertThat(byItem.get(item).totalRaw()).as(item + " totalRaw").isEqualTo(expected);
    }
}
