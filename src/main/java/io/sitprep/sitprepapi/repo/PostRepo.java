package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PostRepo extends JpaRepository<Post, Long> {

    @Query("SELECT p FROM Post p WHERE p.groupId = :groupId ORDER BY p.timestamp DESC")
    List<Post> findPostsByGroupId(@Param("groupId") String groupId);

    // Backfill by UPDATED time (ascending for client merge)
    List<Post> findByGroupIdAndUpdatedAtAfterOrderByUpdatedAtAsc(String groupId, Instant since);

    @Query("""
           SELECT p FROM Post p
           WHERE p.groupId IN :groupIds
             AND p.timestamp = (
                 SELECT MAX(p2.timestamp) FROM Post p2 WHERE p2.groupId = p.groupId
             )
           """)
    List<Post> findLatestPostsByGroupIds(@Param("groupIds") List<String> groupIds);
}
