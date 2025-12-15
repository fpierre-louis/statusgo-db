package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.RSGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RSGroupRepo extends JpaRepository<RSGroup, String> {
    List<RSGroup> findByIsPrivateFalseOrderByCreatedAtDesc();
    List<RSGroup> findByOwnerEmailIgnoreCaseOrderByCreatedAtDesc(String ownerEmail);
}