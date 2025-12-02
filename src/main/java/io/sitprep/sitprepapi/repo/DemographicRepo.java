// src/main/java/io/sitprep/sitprepapi/repo/DemographicRepo.java
package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Demographic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DemographicRepo extends JpaRepository<Demographic, String> {

    /**
     * Simple existence check used by readiness computation.
     * This will NOT throw even if multiple rows exist for the same ownerEmail.
     */
    boolean existsByOwnerEmailIgnoreCase(String ownerEmail);

    /**
     * Kept for backwards compatibility if other code needs the actual entity.
     * NOTE: This can throw NonUniqueResultException if there are multiple rows.
     */
    Optional<Demographic> findByOwnerEmailIgnoreCase(String ownerEmail);

    // ✅ Admin Email – MEMBER OF collection field
    @Query("SELECT d FROM Demographic d WHERE :adminEmail MEMBER OF d.adminEmails")
    List<Demographic> findByAdminEmail(String adminEmail);
}