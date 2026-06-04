package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.PublisherPublishAudit;

import java.time.Instant;

public record PublisherPublishAuditDto(
        Long id,
        String eventType,
        String actorEmail,
        String publisherEmail,
        String organizationId,
        String organizationName,
        String organizationKind,
        String reachLabel,
        String permanentAddress,
        String temporaryEventAddress,
        Double latitude,
        Double longitude,
        String postTable,
        Long postId,
        String message,
        PublisherPublishAudit.ReviewStatus reviewStatus,
        String reviewerEmail,
        String reviewerNotes,
        Instant reviewedAt,
        Instant createdAt
) {
    public static PublisherPublishAuditDto from(PublisherPublishAudit row) {
        return new PublisherPublishAuditDto(
                row.getId(),
                row.getEventType(),
                row.getActorEmail(),
                row.getPublisherEmail(),
                row.getOrganizationId(),
                row.getOrganizationName(),
                row.getOrganizationKind(),
                row.getReachLabel(),
                row.getPermanentAddress(),
                row.getTemporaryEventAddress(),
                row.getLatitude(),
                row.getLongitude(),
                row.getPostTable(),
                row.getPostId(),
                row.getMessage(),
                row.getReviewStatus(),
                row.getReviewerEmail(),
                row.getReviewerNotes(),
                row.getReviewedAt(),
                row.getCreatedAt()
        );
    }
}
