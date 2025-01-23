package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Demographic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DemographicRepo extends JpaRepository<Demographic, Long> {

    List<Demographic> findByOwnerEmail(String ownerEmail);

    @Query("SELECT d FROM Demographic d WHERE :adminEmail MEMBER OF d.adminEmails")
    List<Demographic> findByAdminEmail(@Param("adminEmail") String adminEmail);
}