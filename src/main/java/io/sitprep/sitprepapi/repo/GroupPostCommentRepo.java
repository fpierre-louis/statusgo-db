package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GroupPostComment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface GroupPostCommentRepo extends JpaRepository<GroupPostComment, Long> {

    // All comments for one post, oldest -> newest. Legacy / unbounded.
    List<GroupPostComment> findByPostIdOrderByTimestampAsc(Long postId);

    /**
     * Most-recent N comments for one post, descending by id. Cursor
     * pagination entry point — pass {@code Pageable.ofSize(N)} for the
     * initial load (most recent N), then call
     * {@link #findByPostIdAndIdLessThanOrderByIdDesc} with the oldest
     * loaded id for the next-older page. Service layer reverses to
     * chronological for FE consumption.
     */
    List<GroupPostComment> findByPostIdOrderByIdDesc(Long postId, Pageable pageable);

    /** Older N comments before a cursor id. */
    List<GroupPostComment> findByPostIdAndIdLessThanOrderByIdDesc(
            Long postId, Long beforeId, Pageable pageable);

    // Delta by updatedAt (ascending)
    List<GroupPostComment> findByPostIdAndUpdatedAtAfterOrderByUpdatedAtAsc(Long postId, Instant since);

    // Batch for multiple posts (first ordered by postId, then by timestamp)
    List<GroupPostComment> findAllByPostIdInOrderByPostIdAscTimestampAsc(Collection<Long> postIds);
}
