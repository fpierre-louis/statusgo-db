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
        Instant createdAt
) {
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
                row.getCreatedAt()
        );
    }
}
