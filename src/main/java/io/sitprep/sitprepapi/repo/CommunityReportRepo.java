package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.CommunityReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommunityReportRepo extends JpaRepository<CommunityReport, Long> {
    List<CommunityReport> findByStatusOrderByCreatedAtDesc(CommunityReport.ReviewStatus status);

    List<CommunityReport> findTop100ByOrderByCreatedAtDesc();
}
