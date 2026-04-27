package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Repository
public interface EmergencyContactGroupRepo extends JpaRepository<EmergencyContactGroup, Long> {
    List<EmergencyContactGroup> findByOwnerEmail(String ownerEmail);
    List<EmergencyContactGroup> findByOwnerEmailIgnoreCase(String ownerEmail);

    /** Cheap existence check for readiness aggregation. */
    boolean existsByOwnerEmailIgnoreCase(String ownerEmail);

    /**
     * Batched existence lookup. Returns the lower-cased subset of {@code emails}
     * that have at least one EmergencyContactGroup row.
     */
    @Query("SELECT DISTINCT LOWER(g.ownerEmail) FROM EmergencyContactGroup g WHERE LOWER(g.ownerEmail) IN :emails")
    Set<String> findOwnerEmailsIn(@Param("emails") Collection<String> emails);
}
