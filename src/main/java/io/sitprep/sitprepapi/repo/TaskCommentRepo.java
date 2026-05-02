package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.TaskComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link TaskComment}. Mirrors {@code CommentRepo} so the
 * eventual entity unification is mechanical — same finder shapes, same
 * batched-load convention.
 */
public interface TaskCommentRepo extends JpaRepository<TaskComment, Long> {

    /** All comments for one task, oldest → newest. */
    List<TaskComment> findByTaskIdOrderByTimestampAsc(Long taskId);

    /** Delta by updatedAt (ascending) — used by backfill polling on the FE. */
    List<TaskComment> findByTaskIdAndUpdatedAtAfterOrderByUpdatedAtAsc(Long taskId, Instant since);

    /** Batched roster for multiple tasks (oldest → newest within each task). */
    List<TaskComment> findAllByTaskIdInOrderByTaskIdAscTimestampAsc(Collection<Long> taskIds);

    /**
     * Per-task comment count for a list of tasks. Used by
     * {@code TaskService.withEngagement} to fold {@code commentsCount}
     * onto every TaskDto in one query rather than N. Returns
     * {@code Object[]{taskId, count}} rows; service layer maps to
     * {@code Map<Long, Long>}.
     */
    @Query("SELECT c.taskId, COUNT(c) FROM TaskComment c " +
           "WHERE c.taskId IN :taskIds " +
           "GROUP BY c.taskId")
    List<Object[]> countByTaskIdIn(@Param("taskIds") Collection<Long> taskIds);
}
