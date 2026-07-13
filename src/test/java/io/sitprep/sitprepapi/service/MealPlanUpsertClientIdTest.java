package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the meal-plan create path (fixes the recurring
 * {@code POST /api/mealPlans 409 Conflict}).
 *
 * <p>The FE seeds {@code mealPlanData.id = Date.now()} for its local cache and
 * spreads it into the create body, but {@code MealPlanData.id} is DB
 * IDENTITY-generated. Before the fix, {@code upsert()}'s new-record branch
 * passed that non-null id straight to {@code repository.save()}, so Spring Data
 * ran {@code merge()} on a detached row that doesn't exist (common after a local
 * DB rebuild) → Hibernate {@code StaleObjectStateException} →
 * {@code OptimisticLockingFailureException} → HTTP 409. {@code upsert()} now
 * nulls {@code incoming.id} on the create path, forcing a clean INSERT with a
 * server-owned id.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class MealPlanUpsertClientIdTest {

    @Autowired MealPlanDataService service;
    @Autowired MealPlanDataRepo repo;

    @Test
    void create_ignores_client_supplied_id_and_inserts_cleanly() {
        // A synthetic client id (FE's Date.now() shape) that matches no DB row.
        final long staleClientId = 1_720_000_000_000L;

        MealPlan menu = new MealPlan();
        menu.setMeals(Map.of("breakfast", "Oatmeal"));

        MealPlanData incoming = new MealPlanData();
        incoming.setId(staleClientId);                 // the poison the FE sends
        incoming.setOwnerEmail("client-id-create@example.com");
        incoming.setNumberOfMenuOptions(1);
        incoming.setMealPlan(List.of(menu));

        // Pre-fix this threw OptimisticLockingFailureException (→ 409) because
        // save() ran merge() on a detached, non-existent row. It must now insert.
        MealPlanData saved = service.upsert(incoming);

        assertThat(saved.getId())
                .as("server owns the IDENTITY id; the client's synthetic id is ignored")
                .isNotNull()
                .isNotEqualTo(staleClientId);
        assertThat(repo.findFirstByOwnerEmailIgnoreCase("client-id-create@example.com"))
                .as("the plan was actually inserted")
                .isPresent();
    }
}
