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
 * {@link ScenarioRun} on REAL Postgres over the replayed Flyway schema (V1..V104).
 *
 * <p>Why it exists: {@code ScenarioRun}'s JSON columns are the first in the
 * codebase mapped WITHOUT {@code columnDefinition = "jsonb"} (so H2 can build
 * the table). Production boots with {@code ddl-auto: validate}; this runs
 * Hibernate's schema validation for EVERY entity against the real schema, so a
 * jsonb/json mismatch that would stop prod from booting fails here first.</p>
 *
 * <p><b>Runs only when {@code PRACTICE_PG_IT=true}</b>, with the datasource
 * pointed at a throwaway local DB built by replaying {@code db/migration/*.sql}
 * through psql (EXEC-B). Skipped on CI/Heroku.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/sitprep_practice_it",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "PRACTICE_PG_IT", matches = "true")
class ScenarioRunPostgresIT {

    @Autowired
    private ScenarioRunRepo repo;

    @Autowired
    private JdbcTemplate jdbc;

    private ScenarioRun personal(String email) {
        ScenarioRun r = new ScenarioRun();
        r.setUserEmail(email);
        r.setScenarioKey("comms-outage-family-reconnect");
        r.setContentVersion(1);
        r.setContentHash("sha256:abc");
        r.setStatus(ScenarioRun.Status.IN_PROGRESS);
        r.setLastNodeKey("calls_failing");
        return r;
    }

    @Test
    void schemaValidatesAndJsonRoundTripsAsJsonb() {
        ScenarioRun r = personal("pg@example.com");
        r.setDecisionTrace(List.of(new ScenarioRun.TraceStep("calls_failing", "short_text", "2026-10-08T15:00:00Z")));
        r.setDebriefTags(List.of("communicated_clearly"));
        Long id = repo.saveAndFlush(r).getId();

        String type = jdbc.queryForObject(
                "SELECT pg_typeof(decision_trace)::text FROM scenario_run WHERE id = ?", String.class, id);
        assertThat(type).isEqualTo("jsonb");
        String choice = jdbc.queryForObject(
                "SELECT decision_trace->0->>'choiceKey' FROM scenario_run WHERE id = ?", String.class, id);
        assertThat(choice).isEqualTo("short_text");
        assertThat(repo.findById(id).orElseThrow().getDecisionTrace()).hasSize(1);
    }

    @Test
    void oneInProgressPersonalRunPerScenario() {
        repo.saveAndFlush(personal("dup@example.com"));
        assertThatThrownBy(() -> repo.saveAndFlush(personal("dup@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
