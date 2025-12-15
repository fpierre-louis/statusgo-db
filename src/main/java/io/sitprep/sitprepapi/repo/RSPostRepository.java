package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.RSPost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RSPostRepository extends JpaRepository<RSPost, UUID> {
    List<RSPost> findByRsGroupIdAndIsDeletedFalseOrderByCreatedAtDesc(String rsGroupId);
}