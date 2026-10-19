package io.sitprep.sitprepapi.practice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FamilyPracticeCompletion} on REAL Postgres over the replayed Flyway
 * schema (V1..V113). Production boots with {@code ddl-auto: validate}, so this
 * validates every entity against the real schema (V113's jsonb debrief_tags,
 * timestamptz, row_version) and proves the household+activity+version unique
 * constraint that makes completion idempotent.
 *
 * <p><b>Runs only when {@code PRACTICE_PG_IT=true}</b>, against a throwaway
 * local DB built by replaying {@code db/migration/*.sql} through psql
 * ({@code PRACTICE_PG_URL}, default {@code sitprep_practice_it}). Skipped on
 * CI/Heroku; never points at prod.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@TestPropertySource(properties = {
        "spring.datasource.url=${PRACTICE_PG_URL:jdbc:postgresql://localhost:5432/sitprep_practice_it}",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "PRACTICE_PG_IT", matches = "true")
class FamilyPracticeCompletionPostgresIT {

    @Autowired
    private FamilyPracticeCompletionRepo repo;

    @Autowired
    private JdbcTemplate jdbc;

    private FamilyPracticeCompletion row(String householdId) {
        FamilyPracticeCompletion r = new FamilyPracticeCompletion();
        r.setHouseholdId(householdId);
        r.setActivityKey("kit-builder");
        r.setContentVersion(1);
        r.setContentHash("sha256:reviewed");
        r.setCompletedByEmail("adult@example.com");
        r.setDebriefTags(List.of("discussed_core_kit_items", "discussed_household_specific_needs"));
        return r;
    }

    private String household() {
        String id = "pg-it-hh-" + System.nanoTime();
        jdbc.update("INSERT INTO groups (group_id, group_type, group_name) VALUES (?, 'Household', 'PG IT')", id);
        return id;
    }

    @Test
    void schemaValidatesAndTagsRoundTripAsJsonb() {
        String hh = household();
        FamilyPracticeCompletion saved = repo.saveAndFlush(row(hh));

        assertThat(jdbc.queryForObject(
                "SELECT jsonb_typeof(debrief_tags) FROM family_practice_completion WHERE id = ?", String.class, saved.getId()))
                .isEqualTo("array");
        assertThat(repo.findByHouseholdIdAndActivityKeyAndContentVersion(hh, "kit-builder", 1))
                .get().extracting(FamilyPracticeCompletion::getDebriefTags)
                .isEqualTo(List.of("discussed_core_kit_items", "discussed_household_specific_needs"));
    }

    @Test
    void oneCompletionPerHouseholdActivityAndVersion() {
        String hh = household();
        repo.saveAndFlush(row(hh));
        assertThatThrownBy(() -> repo.saveAndFlush(row(hh))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
