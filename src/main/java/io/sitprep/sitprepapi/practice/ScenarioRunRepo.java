package io.sitprep.sitprepapi.practice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ScenarioRunRepo extends JpaRepository<ScenarioRun, Long> {

    /** Has this household finished any scenario (the Rehearsed Together token, EXEC-I4). */
    boolean existsByHouseholdIdAndStatus(String householdId, ScenarioRun.Status status);

    Optional<ScenarioRun> findFirstByHouseholdIdAndScenarioKeyAndStatus(
            String householdId, String scenarioKey, ScenarioRun.Status status);

    Optional<ScenarioRun> findFirstByUserEmailAndScenarioKeyAndStatusAndHouseholdIdIsNull(
            String userEmail, String scenarioKey, ScenarioRun.Status status);

    List<ScenarioRun> findByHouseholdIdOrderByStartedAtDesc(String householdId);

    List<ScenarioRun> findByUserEmailAndHouseholdIdIsNullOrderByStartedAtDesc(String userEmail);

    /**
     * Retention sweep (12 months of event history): finished runs older than
     * {@code cutoff}, EXCEPT the newest COMPLETED run per (household, scenario)
     * and, for personal runs (household_id NULL), per (user_email, scenario).
     * IN_PROGRESS is never selected. "Newest" is completed_at, then id. Native
     * so the keep-latest rule is one set-based statement; the {@code n} row is
     * a newer COMPLETED run in the same scope, and its existence frees this row.
     */
    @Query(nativeQuery = true, value = """
            SELECT r.id FROM scenario_run r
            WHERE r.status IN ('COMPLETED', 'ABANDONED')
              AND COALESCE(r.completed_at, r.abandoned_at, r.updated_at) < :cutoff
              AND (r.status = 'ABANDONED' OR EXISTS (
                    SELECT 1 FROM scenario_run n
                    WHERE n.status = 'COMPLETED'
                      AND n.scenario_key = r.scenario_key
                      AND ((r.household_id IS NOT NULL AND n.household_id = r.household_id)
                        OR (r.household_id IS NULL AND n.household_id IS NULL AND n.user_email = r.user_email))
                      AND (COALESCE(n.completed_at, n.updated_at) > COALESCE(r.completed_at, r.updated_at)
                        OR (COALESCE(n.completed_at, n.updated_at) = COALESCE(r.completed_at, r.updated_at)
                            AND n.id > r.id))))
            ORDER BY r.id
            LIMIT :batch
            """)
    List<Long> findSweepableIds(@Param("cutoff") java.time.Instant cutoff, @Param("batch") int batch);

    @Transactional
    @Modifying
    @Query("DELETE FROM ScenarioRun r WHERE r.id IN :ids")
    int deleteByIdIn(@Param("ids") java.util.Collection<Long> ids);

    /** Clear-history: every status for one household; never matches a personal run (household_id NULL). */
    @Modifying
    @Query("DELETE FROM ScenarioRun r WHERE r.householdId = :householdId")
    int deleteAllByHousehold(@Param("householdId") String householdId);
}
