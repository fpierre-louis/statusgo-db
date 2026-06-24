package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;

public interface AdminAuditLogRepo extends JpaRepository<AdminAuditLog, Long>, JpaSpecificationExecutor<AdminAuditLog> {
    List<AdminAuditLog> findByTargetTypeAndActionInOrderByAtAsc(String targetType, Collection<String> actions);
}
