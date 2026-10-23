package io.sitprep.sitprepapi.routine;

import io.sitprep.sitprepapi.practice.FamilyPracticeCompletionRepo;
import io.sitprep.sitprepapi.practice.PracticeCheckInAnswerRepo;
import io.sitprep.sitprepapi.practice.PracticeHistoryService;
import io.sitprep.sitprepapi.practice.PracticeSkillStateRepo;
import io.sitprep.sitprepapi.practice.PracticeTogetherSessionRepo;
import io.sitprep.sitprepapi.practice.ScenarioRunRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.AdminAuditLogService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link HouseholdRoutine} on REAL Postgres over the replayed Flyway schema (V1..V124), with
 * {@code ddl-auto: validate}: the entity matches V124 (jsonb slot_values, timestamptz, row_version),
 * (household, routine) is unique, the row goes with its household, and "Clear practice history"
 * leaves routines alone (they are plan data, not history).
 *
 * <p><b>Runs only when {@code PRACTICE_PG_IT=true}</b>, against a throwaway local DB built by
 * replaying {@code db/migration/*.sql} through psql ({@code PRACTICE_PG_URL}, default
 * {@code sitprep_practice_it}). Skipped on CI/Heroku; never points at prod.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@TestPropertySource(properties = {
        "spring.datasource.url=${PRACTICE_PG_URL:jdbc:postgresql://localhost:5432/sitprep_practice_it}",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "PRACTICE_PG_IT", matches = "true")
class HouseholdRoutinePostgresIT {

    @Autowired HouseholdRoutineRepo repo;
    @Autowired GroupRepo groupRepo;
    @Autowired ScenarioRunRepo runRepo;
    @Autowired FamilyPracticeCompletionRepo completionRepo;
    @Autowired PracticeCheckInAnswerRepo answerRepo;
    @Autowired PracticeSkillStateRepo skillStateRepo;
    @Autowired PracticeTogetherSessionRepo sessionRepo;
    @Autowired JdbcTemplate jdbc;

    private String household() {
        String id = "pg-routine-hh-" + System.nanoTime();
        jdbc.update("INSERT INTO groups (group_id, group_type, group_name) VALUES (?, 'Household', 'PG IT')", id);
        return id;
    }

    private HouseholdRoutine row(String hh, String key, String thenText) {
        HouseholdRoutine r = new HouseholdRoutine();
        r.setHouseholdId(hh);
        r.setRoutineKey(key);
        r.setContentVersion(1);
        r.setHazardKey("tornado");
        r.setIfText("If a tornado warning comes at night");
        r.setThenText(thenText);
        Map<String, HouseholdRoutine.SlotValue> slots = new LinkedHashMap<>();
        slots.put("who", new HouseholdRoutine.SlotValue("Sam", "user:u-3"));
        slots.put("place", new HouseholdRoutine.SlotValue("Hall closet", null));
        r.setSlotValues(slots);
        r.setCreatedAt(Instant.parse("2026-10-23T12:00:00Z"));
        r.setUpdatedAt(Instant.parse("2026-10-23T12:00:00Z"));
        r.setCreatedByEmail("owner@example.com");
        r.setUpdatedByEmail("owner@example.com");
        return r;
    }

    @Test
    void theEntityMatchesV124AndTheSlotValuesRoundTripThroughJsonb() {
        String hh = household();
        HouseholdRoutine saved = repo.saveAndFlush(row(hh, "tornado-night-routine", "Sam wakes everyone."));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getRowVersion()).isZero();

        jdbc.execute("SELECT 1"); // keep the persistence context honest below
        HouseholdRoutine read = repo.findByHouseholdIdAndRoutineKey(hh, "tornado-night-routine").orElseThrow();
        assertThat(read.getSlotValues()).containsOnlyKeys("who", "place");
        assertThat(read.getSlotValues().get("who")).isEqualTo(new HouseholdRoutine.SlotValue("Sam", "user:u-3"));
        assertThat(read.getSlotValues().get("place").refId()).isNull();
        assertThat(jdbc.queryForObject("SELECT slot_values->'who'->>'value' FROM household_routine WHERE id = ?", String.class, saved.getId()))
                .isEqualTo("Sam");
    }

    @Test
    void upsertIsUniquePerHouseholdAndRoutine() {
        String hh = household();
        repo.saveAndFlush(row(hh, "tornado-night-routine", "first"));
        HouseholdRoutine again = repo.findByHouseholdIdAndRoutineKey(hh, "tornado-night-routine").orElseThrow();
        again.setThenText("second");
        HouseholdRoutine updated = repo.saveAndFlush(again);
        assertThat(updated.getRowVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM household_routine WHERE household_id = ?", Integer.class, hh)).isEqualTo(1);

        // A second INSERT for the same pair is refused by the constraint, not silently doubled.
        assertThatThrownBy(() -> repo.saveAndFlush(row(hh, "tornado-night-routine", "duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentRoutinesAndHouseholdsDoNotCollide() {
        String a = household();
        String b = household();
        repo.saveAndFlush(row(a, "tornado-night-routine", "a"));
        repo.saveAndFlush(row(a, "fire-exit-routine", "a2"));
        repo.saveAndFlush(row(b, "tornado-night-routine", "b"));
        assertThat(repo.findByHouseholdId(a)).hasSize(2);
        assertThat(repo.findByHouseholdId(b)).hasSize(1);
        assertThat(repo.deleteByHouseholdIdAndRoutineKey(a, "fire-exit-routine")).isEqualTo(1);
        assertThat(repo.deleteByHouseholdIdAndRoutineKey(a, "fire-exit-routine")).isZero();
    }

    @Test
    void theRowGoesWithItsHousehold() {
        String hh = household();
        repo.saveAndFlush(row(hh, "tornado-night-routine", "gone with the household"));
        jdbc.update("DELETE FROM groups WHERE group_id = ?", hh);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM household_routine WHERE household_id = ?", Integer.class, hh)).isZero();
    }

    @Test
    void aRoutineNeedsARealHousehold() {
        assertThatThrownBy(() -> repo.saveAndFlush(row("no-such-household", "tornado-night-routine", "orphan")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void clearingPracticeHistoryLeavesRoutinesAlone() {
        String hh = household();
        repo.saveAndFlush(row(hh, "tornado-night-routine", "plan data, not history"));
        // Practice history that "Clear practice history" does remove.
        jdbc.update("""
                INSERT INTO scenario_run (household_id, user_email, scenario_key, content_version, content_hash,
                    status, started_at, updated_at, decision_trace, debrief_tags, row_version)
                VALUES (?, 'a@x.com', 's1', 1, 'h', 'COMPLETED', now(), now(), '[]'::jsonb, '[]'::jsonb, 0)""", hh);
        jdbc.update("""
                INSERT INTO family_practice_completion (household_id, activity_key, content_version, content_hash, completed_at)
                VALUES (?, 'kit-builder', 1, 'h', now())""", hh);

        HouseholdAccessService access = mock(HouseholdAccessService.class);
        PracticeHistoryService history = new PracticeHistoryService(access, groupRepo, runRepo, completionRepo, answerRepo,
                skillStateRepo, sessionRepo, mock(AdminAuditLogService.class), mock(WebSocketMessageSender.class));
        PracticeHistoryService.ClearResult result = history.clear("owner@example.com", hh, true);

        assertThat(result.removed().scenarioRuns()).isEqualTo(1);
        assertThat(result.removed().familyCompletions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scenario_run WHERE household_id = ?", Integer.class, hh)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM household_routine WHERE household_id = ?", Integer.class, hh)).isEqualTo(1);
        assertThat(repo.findByHouseholdIdAndRoutineKey(hh, "tornado-night-routine")).isPresent();
    }
}
