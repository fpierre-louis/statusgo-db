package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HazardReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface HazardReportRepo extends JpaRepository<HazardReport, Long> {

    /** Not cleared and not yet expired. The table stays small: reports expire. */
    List<HazardReport> findByClearedAtIsNullAndExpiresAtAfter(Instant now);

    /**
     * A person's reports since {@code since} — the rate limit and the
     * duplicate check. The reporter lives on the post row, not here, so this
     * joins through it (the hazard table never stores who reported).
     */
    @Query("""
        SELECT h FROM HazardReport h, Post p
         WHERE p.id = h.taskId
           AND LOWER(p.requesterEmail) = LOWER(:email)
           AND h.reportedAt >= :since
        """)
    List<HazardReport> findByReporterSince(@Param("email") String email, @Param("since") Instant since);
}
