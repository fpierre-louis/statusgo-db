package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmergencyContactGroupRepo extends JpaRepository<EmergencyContactGroup, Long> {
    List<EmergencyContactGroup> findByOwnerEmail(String ownerEmail);
    List<EmergencyContactGroup> findByOwnerEmailIgnoreCase(String ownerEmail);
}
