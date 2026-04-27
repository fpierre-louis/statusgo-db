// src/main/java/io/sitprep/sitprepapi/repo/DemographicRepo.java
package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Demographic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface DemographicRepo extends JpaRepository<Demographic, String> {

    /**
     * Simple existence check used by readiness computation.
     * This will NOT throw even if multiple rows exist for the same ownerEmail.
     */
    boolean existsByOwnerEmailIgnoreCase(String ownerEmail);

    /**
     * Batched existence lookup. Returns the lower-cased subset of {@code emails}
     * that have at least one Demographic row. Replaces N {@link #existsByOwnerEmailIgnoreCase}
     * calls inside readiness aggregation loops.
     */
    @Query("SELECT DISTINCT LOWER(d.ownerEmail) FROM Demographic d WHERE LOWER(d.ownerEmail) IN :emails")
    Set<String> findOwnerEmailsIn(@Param("emails") Collection<String> emails);

    /**
     * Kept for backwards compatibility if other code needs the actual entity.
     * NOTE: This can throw NonUniqueResultException if there are multiple rows.
     */
    Optional<Demographic> findByOwnerEmailIgnoreCase(String ownerEmail);

    /**
     * Duplicate-tolerant lookup — picks the most recently inserted row.
     * Use this in new code; the plain {@link #findByOwnerEmailIgnoreCase} can
     * throw NonUniqueResultException when legacy data has duplicate rows.
     */
    Optional<Demographic> findFirstByOwnerEmailIgnoreCaseOrderByIdDesc(String ownerEmail);

    // ✅ Admin Email – MEMBER OF collection field
    @Query("SELECT d FROM Demographic d WHERE :adminEmail MEMBER OF d.adminEmails")
    List<Demographic> findByAdminEmail(String adminEmail);
}