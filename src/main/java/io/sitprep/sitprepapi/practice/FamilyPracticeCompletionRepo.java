package io.sitprep.sitprepapi.practice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface FamilyPracticeCompletionRepo extends JpaRepository<FamilyPracticeCompletion, Long> {
    Optional<FamilyPracticeCompletion> findByHouseholdIdAndActivityKeyAndContentVersion(
            String householdId, String activityKey, int contentVersion);

    List<FamilyPracticeCompletion> findByHouseholdIdOrderByCompletedAtDesc(String householdId);

    /**
     * Retention sweep: completions older than {@code cutoff} that a newer row for
     * the same (household, activity) supersedes. The newest row per activity is
     * the household's operational "done" state and is never selected.
     */
    @Query(nativeQuery = true, value = """
            SELECT c.id FROM family_practice_completion c
            WHERE c.completed_at < :cutoff
              AND EXISTS (
                    SELECT 1 FROM family_practice_completion n
                    WHERE n.household_id = c.household_id
                      AND n.activity_key = c.activity_key
                      AND (n.completed_at > c.completed_at
                        OR (n.completed_at = c.completed_at AND n.id > c.id)))
            ORDER BY c.id
            LIMIT :batch
            """)
    List<Long> findSweepableIds(@Param("cutoff") java.time.Instant cutoff, @Param("batch") int batch);

    @Transactional
    @Modifying
    @Query("DELETE FROM FamilyPracticeCompletion c WHERE c.id IN :ids")
    int deleteByIdIn(@Param("ids") java.util.Collection<Long> ids);

    /** Clear-history: every completion for one household. */
    @Modifying
    @Query("DELETE FROM FamilyPracticeCompletion c WHERE c.householdId = :householdId")
    int deleteAllByHousehold(@Param("householdId") String householdId);
}
