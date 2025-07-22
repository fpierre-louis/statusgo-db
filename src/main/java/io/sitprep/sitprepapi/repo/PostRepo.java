package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostRepo extends JpaRepository<Post, Long> {
    @Query("SELECT p FROM Post p WHERE p.groupId = :groupId ORDER BY p.timestamp DESC")
    List<Post> findPostsByGroupId(@Param("groupId") String groupId);

}
