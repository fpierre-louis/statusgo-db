package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PublisherPublishAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublisherPublishAuditRepo extends JpaRepository<PublisherPublishAudit, Long> {
}
