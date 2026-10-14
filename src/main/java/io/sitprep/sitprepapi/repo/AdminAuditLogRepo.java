package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;

public interface AdminAuditLogRepo extends JpaRepository<AdminAuditLog, Long>, JpaSpecificationExecutor<AdminAuditLog> {
    List<AdminAuditLog> findByTargetTypeAndActionInOrderByAtAsc(String targetType, Collection<String> actions);

    /** One batched read of the audit rows for a page of targets (group content tombstones, B6/FD-06). */
    List<AdminAuditLog> findByTargetTypeAndActionAndTargetIdIn(String targetType, String action,
                                                               Collection<String> targetIds);
}
