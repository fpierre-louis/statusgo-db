// src/main/java/io/sitprep/sitprepapi/repo/RSGroupRepo.java
package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.RSGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RSGroupRepo extends JpaRepository<RSGroup, String> {

    // ✅ Discoverable public groups (for browsing)
    List<RSGroup> findByIsPrivateFalseAndIsDiscoverableTrueOrderByCreatedAtDesc();

    // Keep: owner groups (dashboard)
    List<RSGroup> findByOwnerEmailIgnoreCaseOrderByCreatedAtDesc(String ownerEmail);

    // Optional helper for lightweight search later (name/description)
    @Query("""
        select g
        from RSGroup g
        where g.isPrivate = false
          and g.isDiscoverable = true
        order by g.createdAt desc
    """)
    List<RSGroup> findDiscoverablePublic();
}