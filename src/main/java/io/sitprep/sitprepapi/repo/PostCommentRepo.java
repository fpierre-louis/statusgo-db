package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link PostComment}. Mirrors {@code GroupPostCommentRepo} so the
 * eventual entity unification is mechanical — same finder shapes, same
 * batched-load convention.
 */
public interface PostCommentRepo extends JpaRepository<PostComment, Long> {

    /** All comments for one task, oldest → newest. */
    List<PostComment> findByPostIdOrderByTimestampAsc(Long postId);

    /** Delta by updatedAt (ascending) — used by backfill polling on the FE. */
    List<PostComment> findByPostIdAndUpdatedAtAfterOrderByUpdatedAtAsc(Long postId, Instant since);

    /** Batched roster for multiple tasks (oldest → newest within each task). */
    List<PostComment> findAllByPostIdInOrderByPostIdAscTimestampAsc(Collection<Long> postIds);

    /**
     * Per-task comment count for a list of tasks. Used by
     * {@code PostService.withEngagement} to fold {@code commentsCount}
     * onto every PostDto in one query rather than N. Returns
     * {@code Object[]{postId, count}} rows; service layer maps to
     * {@code Map<Long, Long>}.
     */
    @Query("SELECT c.postId, COUNT(c) FROM PostComment c " +
           "WHERE c.postId IN :postIds " +
           "GROUP BY c.postId")
    List<Object[]> countByPostIdIn(@Param("postIds") Collection<Long> postIds);
}
