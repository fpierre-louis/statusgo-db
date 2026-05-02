package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.TaskReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link TaskReaction}. Mirrors {@code PostReactionRepo} so
 * future unification is mechanical.
 */
@Repository
public interface TaskReactionRepo extends JpaRepository<TaskReaction, Long> {

    List<TaskReaction> findByTaskId(Long taskId);

    /** Batched roster fetch for a list of tasks (community feed listing). */
    List<TaskReaction> findByTaskIdIn(Collection<Long> taskIds);

    /** Used to detect "already reacted" so add-twice is a no-op. */
    Optional<TaskReaction> findByTaskIdAndUserEmailIgnoreCaseAndEmoji(
            Long taskId, String userEmail, String emoji);

    /**
     * Did this viewer react with this emoji on any of the supplied tasks?
     * Used by the listing path to populate {@code viewerThanked} per row
     * in one query rather than N separate lookups.
     */
    @Query("SELECT r.taskId FROM TaskReaction r " +
           "WHERE r.taskId IN :taskIds " +
           "AND lower(r.userEmail) = lower(:userEmail) " +
           "AND r.emoji = :emoji")
    List<Long> findTaskIdsWhereViewerReacted(@Param("taskIds") Collection<Long> taskIds,
                                             @Param("userEmail") String userEmail,
                                             @Param("emoji") String emoji);

    @Modifying
    @Query("DELETE FROM TaskReaction r WHERE r.taskId = :taskId " +
           "AND lower(r.userEmail) = lower(:userEmail) AND r.emoji = :emoji")
    int deleteByTaskUserEmoji(@Param("taskId") Long taskId,
                              @Param("userEmail") String userEmail,
                              @Param("emoji") String emoji);

    @Modifying
    @Query("DELETE FROM TaskReaction r WHERE r.taskId = :taskId")
    void deleteAllByTaskId(@Param("taskId") Long taskId);
}
