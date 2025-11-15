package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface CommentRepo extends JpaRepository<Comment, Long> {

    // All comments for one post, oldest -> newest
    List<Comment> findByPostIdOrderByTimestampAsc(Long postId);

    // Delta by updatedAt (ascending)
    List<Comment> findByPostIdAndUpdatedAtAfterOrderByUpdatedAtAsc(Long postId, Instant since);

    // Batch for multiple posts (first ordered by postId, then by timestamp)
    List<Comment> findAllByPostIdInOrderByPostIdAscTimestampAsc(Collection<Long> postIds);
}