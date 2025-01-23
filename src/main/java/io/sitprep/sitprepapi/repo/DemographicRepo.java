package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Demographic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DemographicRepo extends JpaRepository<Demographic, Long> {

    Optional<Demographic> findByOwnerEmail(String ownerEmail);

    @Query("SELECT d FROM Demographic d WHERE :adminEmail MEMBER OF d.adminEmails")
    List<Demographic> findByAdminEmail(String adminEmail);
}
