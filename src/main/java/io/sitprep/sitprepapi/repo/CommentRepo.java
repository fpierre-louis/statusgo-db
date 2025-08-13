package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CommentRepo extends JpaRepository<Comment, Long> {
    List<Comment> findByPostId(Long postId);

    // Fetch comments for many posts in one round-trip (per-post newest first)
    @Query("SELECT c FROM Comment c WHERE c.postId IN :postIds ORDER BY c.postId ASC, c.timestamp DESC")
    List<Comment> findByPostIdInOrderByPostIdAndTimestampDesc(@Param("postIds") Collection<Long> postIds);
}
