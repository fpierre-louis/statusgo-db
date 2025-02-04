package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.EmergencyContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmergencyContactRepo extends JpaRepository<EmergencyContact, Long> {
}
