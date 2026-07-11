package io.sitprep.sitprepapi.domain;

import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the Food Planner per-menu ingredient persistence.
 *
 * <p>{@code MealPlan.ingredients} (Map&lt;String, List&lt;String&gt;&gt;) was
 * originally an illegal @ElementCollection value type that silently dropped the
 * nested lists on round-trip. A 2026-07-11 hotfix hand-serialized it to a TEXT
 * column; it is now mapped natively to a Postgres {@code jsonb} column via
 * Hibernate 6's {@code @JdbcTypeCode(SqlTypes.JSON)}.</p>
 *
 * <p>Unlike the old bridge test (which called the @PrePersist/@PostLoad hooks
 * directly with no database), this drives a real save + reload through the
 * repository against the H2 test database (PostgreSQL mode, {@code jsonb}
 * column built by Hibernate {@code ddl-auto=create-drop}). That exercises the
 * exact JSON JdbcType bind/extract path used in production, so the nested lists
 * can never again silently vanish across a persistence round-trip.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class MealPlanIngredientsPersistenceTest {

    @Autowired
    MealPlanDataRepo repo;

    @Test
    void nested_ingredient_lists_survive_db_round_trip() {
        MealPlan menu = new MealPlan();
        menu.setMeals(Map.of("breakfast", "Cereal with Milk and Fruit"));
        Map<String, List<String>> ingredients = new HashMap<>();
        ingredients.put("breakfast", List.of("Cereal", "Milk", "Fruit"));
        ingredients.put("dinner", List.of("Beef Stew", "Crackers"));
        menu.setIngredients(ingredients);

        String householdId = "hh-" + UUID.randomUUID();
        MealPlanData mpd = new MealPlanData();
        mpd.setOwnerEmail("ingredients-roundtrip@example.com");
        mpd.setHouseholdId(householdId);
        mpd.setMealPlan(List.of(menu));

        repo.save(mpd);

        // Fresh read in a new transaction/session — a true DB round-trip, not
        // the first-level cache. (No surrounding @Transactional, so save() and
        // the query below each run in their own transaction.)
        MealPlanData reloaded = repo.findFirstByHouseholdId(householdId).orElseThrow();

        Map<String, List<String>> loaded = reloaded.getMealPlan().get(0).getIngredients();
        assertThat(loaded)
                .containsEntry("breakfast", List.of("Cereal", "Milk", "Fruit"))
                .containsEntry("dinner", List.of("Beef Stew", "Crackers"))
                .hasSize(2);
    }

    @Test
    void empty_ingredients_round_trip_without_data_loss() {
        MealPlan menu = new MealPlan();
        menu.setMeals(Map.of("breakfast", "Granola Bar"));
        // ingredients left as the default empty map.

        String householdId = "hh-" + UUID.randomUUID();
        MealPlanData mpd = new MealPlanData();
        mpd.setOwnerEmail("ingredients-empty@example.com");
        mpd.setHouseholdId(householdId);
        mpd.setMealPlan(List.of(menu));

        repo.save(mpd);

        MealPlanData reloaded = repo.findFirstByHouseholdId(householdId).orElseThrow();

        // An empty map must not corrupt the row or the sibling `meals` map, and
        // must never reload as unexpected data.
        assertThat(reloaded.getMealPlan()).hasSize(1);
        assertThat(reloaded.getMealPlan().get(0).getMeals())
                .containsEntry("breakfast", "Granola Bar");
        assertThat(reloaded.getMealPlan().get(0).getIngredients()).isNullOrEmpty();
    }
}
