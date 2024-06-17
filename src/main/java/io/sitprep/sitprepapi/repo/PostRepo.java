package io.sitprep.sitprepapi.repo;


import io.sitprep.sitprepapi.domain.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface PostRepo extends JpaRepository<Post, Long> {
    List<Post> findByGroupId(Long groupId);
}

