package io.sitprep.sitprepapi.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time, idempotent migration: drop the legacy UNIQUE constraint on
 * {@code meal_plan_data_v2.owner_email}.
 *
 * <p>Meal plans are becoming household-keyed (docs/WIP_HOUSEHOLD_PLANS.md):
 * a user may author a meal plan for more than one household (their base +
 * any household they admin via cross-household editing), so a single author
 * can own multiple meal-plan rows. The old per-owner UNIQUE constraint
 * forbids that. Hibernate {@code ddl-auto=update} never drops constraints,
 * so we drop it here at boot.</p>
 *
 * <p>Postgres-specific; idempotent (after the constraint is gone the loop
 * finds nothing); failures are logged, not thrown, so a hiccup never blocks
 * startup. DELETE once the migration is universally applied.</p>
 */
@Component
public class MealPlanOwnerUniqueDropRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MealPlanOwnerUniqueDropRunner.class);

    private final JdbcTemplate jdbc;

    public MealPlanOwnerUniqueDropRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbc.execute(
                "DO $$\n" +
                "DECLARE r record;\n" +
                "BEGIN\n" +
                "  FOR r IN\n" +
                "    SELECT con.conname\n" +
                "    FROM pg_constraint con\n" +
                "    JOIN pg_class rel ON rel.oid = con.conrelid\n" +
                "    JOIN pg_attribute att ON att.attrelid = con.conrelid\n" +
                "                          AND att.attnum = ANY(con.conkey)\n" +
                "    WHERE rel.relname = 'meal_plan_data_v2'\n" +
                "      AND con.contype = 'u'\n" +
                "      AND att.attname = 'owner_email'\n" +
                "      AND array_length(con.conkey, 1) = 1\n" +
                "  LOOP\n" +
                "    EXECUTE 'ALTER TABLE meal_plan_data_v2 DROP CONSTRAINT ' || quote_ident(r.conname);\n" +
                "  END LOOP;\n" +
                "END $$;"
            );
            log.info("MealPlan owner_email UNIQUE-drop migration applied (idempotent).");
        } catch (Exception e) {
            log.error("MealPlan owner_email UNIQUE-drop failed; will retry next boot. cause={}",
                    e.getMessage(), e);
        }
    }
}
