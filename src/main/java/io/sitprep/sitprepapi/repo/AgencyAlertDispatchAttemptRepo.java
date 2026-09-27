package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AgencyAlertDispatchAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgencyAlertDispatchAttemptRepo extends JpaRepository<AgencyAlertDispatchAttempt, Long> {
}
