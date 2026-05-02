package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GroupPostComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface GroupPostCommentRepo extends JpaRepository<GroupPostComment, Long> {

    // All comments for one post, oldest -> newest
    List<GroupPostComment> findByPostIdOrderByTimestampAsc(Long postId);

    // Delta by updatedAt (ascending)
    List<GroupPostComment> findByPostIdAndUpdatedAtAfterOrderByUpdatedAtAsc(Long postId, Instant since);

    // Batch for multiple posts (first ordered by postId, then by timestamp)
    List<GroupPostComment> findAllByPostIdInOrderByPostIdAscTimestampAsc(Collection<Long> postIds);
}