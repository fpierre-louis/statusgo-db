package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AgencyAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgencyAlertRepo extends JpaRepository<AgencyAlert, Long> {
    Optional<AgencyAlert> findByDedupKey(String dedupKey);
}
