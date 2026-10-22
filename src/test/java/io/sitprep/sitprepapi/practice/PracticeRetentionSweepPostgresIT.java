package io.sitprep.sitprepapi.practice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retention sweep's native keep-latest SQL on real Postgres, with V121 applied
 * and {@code ddl-auto: validate}. Runs only when {@code PRACTICE_PG_IT=true}; see
 * {@link FamilyPracticeCompletionPostgresIT} for the scratch database.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@TestPropertySource(properties = {
        "spring.datasource.url=${PRACTICE_PG_URL:jdbc:postgresql://localhost:5432/sitprep_practice_it}",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "PRACTICE_PG_IT", matches = "true")
class PracticeRetentionSweepPostgresIT {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    @Autowired ScenarioRunRepo runRepo;
    @Autowired FamilyPracticeCompletionRepo completionRepo;
    @Autowired PracticeCheckInAnswerRepo answerRepo;
    @Autowired JdbcTemplate jdbc;

    private String household() {
        String id = "pg-ret-hh-" + System.nanoTime();
        jdbc.update("INSERT INTO groups (group_id, group_type, group_name) VALUES (?, 'Household', 'PG IT')", id);
        return id;
    }

    private long run(String hh, String email, String status, String at) {
        return jdbc.queryForObject("""
                INSERT INTO scenario_run (household_id, user_email, scenario_key, content_version, content_hash,
                    status, started_at, updated_at, completed_at, abandoned_at, decision_trace, debrief_tags, row_version)
                VALUES (?, ?, 's1', 1, 'h', ?, ?::timestamptz, ?::timestamptz,
                    CASE WHEN ? = 'COMPLETED' THEN ?::timestamptz END,
                    CASE WHEN ? = 'ABANDONED' THEN ?::timestamptz END, '[]'::jsonb, '[]'::jsonb, 0)
                RETURNING id""", Long.class, hh, email, status, at, at, status, at, status, at);
    }

    private long family(String hh, int version, String at) {
        return jdbc.queryForObject("""
                INSERT INTO family_practice_completion (household_id, activity_key, content_version, content_hash, completed_at)
                VALUES (?, 'kit-builder', ?, 'h', ?::timestamptz) RETURNING id""", Long.class, hh, version, at);
    }

    private boolean exists(String table, long id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id) == 1;
    }

    @Test
    void keepLatestRuleHoldsOnPostgres() {
        String hh = household();
        long older = run(hh, "a@x.com", "COMPLETED", "2025-03-01T12:00:00Z");
        long newest = run(hh, "a@x.com", "COMPLETED", "2025-06-01T12:00:00Z");
        long abandoned = run(hh, "a@x.com", "ABANDONED", "2025-03-01T12:00:00Z");
        long active = run(hh, "a@x.com", "IN_PROGRESS", "2025-03-01T12:00:00Z");
        long personalOld = run(null, "pg-ret@x.com", "COMPLETED", "2025-03-01T12:00:00Z");
        long personalNewest = run(null, "pg-ret@x.com", "COMPLETED", "2025-06-01T12:00:00Z");
        long f1 = family(hh, 1, "2025-03-01T12:00:00Z");
        long f2 = family(hh, 2, "2025-06-01T12:00:00Z");

        new PracticeRetentionSweepService(runRepo, completionRepo, answerRepo, Clock.fixed(NOW, ZoneOffset.UTC)).sweepAll();

        assertThat(exists("scenario_run", older)).isFalse();
        assertThat(exists("scenario_run", abandoned)).isFalse();
        assertThat(exists("scenario_run", personalOld)).isFalse();
        assertThat(exists("scenario_run", newest)).isTrue();
        assertThat(exists("scenario_run", active)).isTrue();
        assertThat(exists("scenario_run", personalNewest)).isTrue();
        assertThat(exists("family_practice_completion", f1)).isFalse();
        assertThat(exists("family_practice_completion", f2)).isTrue();
    }

    @Test
    void newFamilyRowsMayOmitTheEmail() {
        String hh = household();
        long id = family(hh, 1, "2026-10-01T12:00:00Z");
        assertThat(jdbc.queryForObject("SELECT completed_by_email FROM family_practice_completion WHERE id = ?",
                String.class, id)).isNull();
    }

    @Test
    void checkInAnswersAreSweptButSkillStateIsKeptOnPostgres() {
        String hh = household();
        long oldAnswer = jdbc.queryForObject("""
                INSERT INTO practice_checkin_answer (household_id, skill_key, checkin_key, content_version, correct, answered_at)
                VALUES (?, 'sk', 'checkin-sk', 1, true, '2025-03-01T12:00:00Z'::timestamptz) RETURNING id""", Long.class, hh);
        long recentAnswer = jdbc.queryForObject("""
                INSERT INTO practice_checkin_answer (household_id, skill_key, checkin_key, content_version, correct, answered_at)
                VALUES (?, 'sk', 'checkin-sk', 1, true, '2026-09-01T12:00:00Z'::timestamptz) RETURNING id""", Long.class, hh);
        long state = jdbc.queryForObject("""
                INSERT INTO practice_skill_state (household_id, skill_key, last_seen_at, interval_days, prior_interval_days,
                    next_due_at, last_result)
                VALUES (?, 'sk', '2025-03-01T12:00:00Z'::timestamptz, 90, 30, '2025-06-01T12:00:00Z'::timestamptz, 'CORRECT')
                RETURNING id""", Long.class, hh);

        new PracticeRetentionSweepService(runRepo, completionRepo, answerRepo, Clock.fixed(NOW, ZoneOffset.UTC)).sweepAll();

        assertThat(exists("practice_checkin_answer", oldAnswer)).isFalse();
        assertThat(exists("practice_checkin_answer", recentAnswer)).isTrue();
        assertThat(exists("practice_skill_state", state)).isTrue();
    }
}
