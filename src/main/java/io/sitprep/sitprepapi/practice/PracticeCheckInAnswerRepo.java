package io.sitprep.sitprepapi.practice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PracticeCheckInAnswerRepo extends JpaRepository<PracticeCheckInAnswer, Long> {

    /** The skill's newest answer (answered_at, then id): the only one a confidence tap may re-schedule. */
    Optional<PracticeCheckInAnswer> findFirstByHouseholdIdAndSkillKeyOrderByAnsweredAtDescIdDesc(
            String householdId, String skillKey);

    /**
     * Retention sweep: answers older than {@code cutoff}. Skill state is a separate
     * table and is never selected here, so scheduling survives the sweep.
     */
    @Query(nativeQuery = true, value = """
            SELECT a.id FROM practice_checkin_answer a
            WHERE a.answered_at < :cutoff
            ORDER BY a.id
            LIMIT :batch
            """)
    List<Long> findSweepableIds(@Param("cutoff") java.time.Instant cutoff, @Param("batch") int batch);

    @Transactional
    @Modifying
    @Query("DELETE FROM PracticeCheckInAnswer a WHERE a.id IN :ids")
    int deleteByIdIn(@Param("ids") java.util.Collection<Long> ids);

    /** Clear-history: every answer for one household. */
    @Modifying
    @Query("DELETE FROM PracticeCheckInAnswer a WHERE a.householdId = :householdId")
    int deleteAllByHousehold(@Param("householdId") String householdId);
}
