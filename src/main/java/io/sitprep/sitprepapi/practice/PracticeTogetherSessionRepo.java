package io.sitprep.sitprepapi.practice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PracticeTogetherSessionRepo extends JpaRepository<PracticeTogetherSession, Long> {

    Optional<PracticeTogetherSession>
            findFirstByHouseholdIdAndActivityKeyAndContentVersionAndExpiresAtAfterOrderByStartedAtDescIdDesc(
                    String householdId, String activityKey, int contentVersion, Instant now);

    /** A household's sessions for one activity, any version (a new session replaces them; completion ends them). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PracticeTogetherSession s WHERE s.householdId = :householdId AND s.activityKey = :activityKey")
    int deleteByHouseholdAndActivity(@Param("householdId") String householdId, @Param("activityKey") String activityKey);

    /** Clear-history: every session for one household. */
    @Modifying
    @Query("DELETE FROM PracticeTogetherSession s WHERE s.householdId = :householdId")
    int deleteAllByHousehold(@Param("householdId") String householdId);

    @Query(nativeQuery = true, value = """
            SELECT s.id FROM practice_together_session s
            WHERE s.expires_at <= :now
            ORDER BY s.id
            LIMIT :batch
            """)
    List<Long> findExpiredIds(@Param("now") Instant now, @Param("batch") int batch);

    @Transactional
    @Modifying
    @Query("DELETE FROM PracticeTogetherSession s WHERE s.id IN :ids")
    int deleteByIdIn(@Param("ids") Collection<Long> ids);
}
