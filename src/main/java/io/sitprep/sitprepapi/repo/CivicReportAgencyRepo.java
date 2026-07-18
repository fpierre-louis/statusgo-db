package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.CivicReportAgency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Civic epic Slice 2 — the civic report ↔ agency tag join. */
public interface CivicReportAgencyRepo extends JpaRepository<CivicReportAgency, Long> {

    /** All tag rows for a report (active + tombstoned), newest agency first is not needed — order by id. */
    List<CivicReportAgency> findByPostId(Long postId);

    /** Active tags for a report (the live agencies). */
    List<CivicReportAgency> findByPostIdAndActiveTrue(Long postId);

    /** Active tags across many reports — the queue folds these in one query. */
    List<CivicReportAgency> findByPostIdInAndActiveTrue(Collection<Long> postIds);

    /** An agency's active tags — the queue's report set for that agency. */
    List<CivicReportAgency> findByAgencyGroupIdAndActiveTrue(String agencyGroupId);

    /** The specific (report, agency) row, if any (active or tombstoned). */
    Optional<CivicReportAgency> findByPostIdAndAgencyGroupId(Long postId, String agencyGroupId);

    /** The claiming row for a report, if any. At most one (partial-unique index + service guard). */
    Optional<CivicReportAgency> findByPostIdAndClaimedTrue(Long postId);

    /** Fast one-claim guard. */
    boolean existsByPostIdAndClaimedTrue(Long postId);
}
