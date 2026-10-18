package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertPushTargetingAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertPushTargetingAuditRepo extends JpaRepository<AlertPushTargetingAudit, Long> {
}
