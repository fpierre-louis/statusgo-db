package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.CommunityReport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

import java.util.List;

public interface CommunityReportRepo extends JpaRepository<CommunityReport, Long> {
    List<CommunityReport> findByStatusOrderByCreatedAtDesc(CommunityReport.ReviewStatus status);

    List<CommunityReport> findTop100ByOrderByCreatedAtDesc();

    /** A group's queue (FD-01): reports on its chat posts and their comments, newest first. */
    @Query("""
           SELECT r FROM CommunityReport r
            WHERE r.targetType IN :types
              AND r.postId IN (SELECT gp.id FROM GroupPost gp WHERE gp.groupId = :groupId)
            ORDER BY r.createdAt DESC
           """)
    List<CommunityReport> findGroupQueue(@Param("groupId") String groupId,
                                         @Param("types") Collection<CommunityReport.TargetType> types,
                                         Pageable page);

    /** {@link #findGroupQueue} filtered by review status. */
    @Query("""
           SELECT r FROM CommunityReport r
            WHERE r.targetType IN :types
              AND r.status = :status
              AND r.postId IN (SELECT gp.id FROM GroupPost gp WHERE gp.groupId = :groupId)
            ORDER BY r.createdAt DESC
           """)
    List<CommunityReport> findGroupQueueByStatus(@Param("groupId") String groupId,
                                                 @Param("types") Collection<CommunityReport.TargetType> types,
                                                 @Param("status") CommunityReport.ReviewStatus status,
                                                 Pageable page);
}
