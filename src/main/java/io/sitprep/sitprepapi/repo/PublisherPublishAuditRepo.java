package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PublisherPublishAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PublisherPublishAuditRepo extends JpaRepository<PublisherPublishAudit, Long> {
    List<PublisherPublishAudit> findByReviewStatusOrderByCreatedAtDesc(
            PublisherPublishAudit.ReviewStatus reviewStatus);

    List<PublisherPublishAudit> findTop100ByOrderByCreatedAtDesc();
}
