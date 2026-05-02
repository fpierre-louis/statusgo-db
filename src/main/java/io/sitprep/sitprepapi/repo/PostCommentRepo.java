package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PostComment;
import org.springframework.data.domain.Pageable;
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

    /** All comments for one task, oldest → newest. Legacy / unbounded. */
    List<PostComment> findByPostIdOrderByTimestampAsc(Long postId);

    /**
     * Most-recent N comments for one post, descending by id. Used by the
     * cursor-based pagination path: pass {@code Pageable.ofSize(N)} for
     * the initial load (most recent N), then call
     * {@link #findByPostIdAndIdLessThanOrderByIdDesc} with the oldest
     * loaded id to fetch the next-older page.
     *
     * <p>The service layer reverses the result so callers see chronological
     * order (oldest → newest) — matches the FE rendering expectation
     * without requiring a sort step on the FE side.</p>
     */
    List<PostComment> findByPostIdOrderByIdDesc(Long postId, Pageable pageable);

    /**
     * Older N comments before a cursor id, descending by id. The FE's
     * "Load earlier" button calls with {@code beforeId = oldest_loaded_id}
     * and limit = page size; service layer reverses for chronological
     * display.
     */
    List<PostComment> findByPostIdAndIdLessThanOrderByIdDesc(
            Long postId, Long beforeId, Pageable pageable);

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
