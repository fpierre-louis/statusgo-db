package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CommentRepo extends JpaRepository<Comment, Long> {
    List<Comment> findByPostId(Long postId);

    // Bulk fetch for many posts at once, ordered deterministically
    @org.springframework.data.jpa.repository.Query("""
        select c from Comment c
        where c.postId in :postIds
        order by c.postId asc, c.timestamp asc
    """)
    List<Comment> findByPostIdInOrder(@org.springframework.data.repository.query.Param("postIds") List<Long> postIds);
}
