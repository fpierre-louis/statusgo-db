package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.CommunityReport;

import java.time.Instant;

public record CommunityReportDto(
        Long id,
        CommunityReport.TargetType targetType,
        Long targetId,
        Long postId,
        String reporterEmail,
        String targetAuthorEmail,
        CommunityReport.Reason reason,
        String details,
        String contentPreview,
        CommunityReport.ReviewStatus status,
        String reviewerEmail,
        String reviewerNotes,
        Instant reviewedAt,
        Instant createdAt,
        /** The group whose content was reported; null for community content (FD-01). */
        String groupId
) {
    /** The filer's receipt for a hidden-author post (HR6): no author. */
    public CommunityReportDto withoutTargetAuthor() {
        return new CommunityReportDto(id, targetType, targetId, postId, reporterEmail, null, reason,
                details, contentPreview, status, reviewerEmail, reviewerNotes, reviewedAt, createdAt, groupId);
    }

    /**
     * A group admin's queue row: no reporter email. Admins act on the content;
     * who reported it stays with the platform moderators, so a report about an
     * admin's own friend (or the admin) can't be traced back to the member.
     */
    public CommunityReportDto withoutReporter() {
        return new CommunityReportDto(id, targetType, targetId, postId, null, targetAuthorEmail, reason,
                details, contentPreview, status, reviewerEmail, reviewerNotes, reviewedAt, createdAt, groupId);
    }

    public CommunityReportDto withGroupId(String gid) {
        return new CommunityReportDto(id, targetType, targetId, postId, reporterEmail, targetAuthorEmail, reason,
                details, contentPreview, status, reviewerEmail, reviewerNotes, reviewedAt, createdAt, gid);
    }

    public static CommunityReportDto from(CommunityReport row) {
        return new CommunityReportDto(
                row.getId(),
                row.getTargetType(),
                row.getTargetId(),
                row.getPostId(),
                row.getReporterEmail(),
                row.getTargetAuthorEmail(),
                row.getReason(),
                row.getDetails(),
                row.getContentPreview(),
                row.getStatus(),
                row.getReviewerEmail(),
                row.getReviewerNotes(),
                row.getReviewedAt(),
                row.getCreatedAt(),
                null
        );
    }
}
