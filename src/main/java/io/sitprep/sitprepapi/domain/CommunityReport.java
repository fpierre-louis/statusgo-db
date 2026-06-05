package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter
@Setter
@Table(
        name = "community_report",
        indexes = {
                @Index(name = "idx_community_report_status_created", columnList = "status,created_at"),
                @Index(name = "idx_community_report_target", columnList = "target_type,target_id"),
                @Index(name = "idx_community_report_post", columnList = "post_id"),
                @Index(name = "idx_community_report_reporter", columnList = "reporter_email")
        }
)
public class CommunityReport {

    public enum TargetType {
        POST,
        COMMENT
    }

    public enum Reason {
        SPAM,
        HARASSMENT,
        MISINFORMATION,
        IMPERSONATION,
        SCAM,
        SAFETY_RISK,
        HATE,
        OTHER
    }

    public enum ReviewStatus {
        PENDING,
        REVIEWED,
        DISMISSED,
        ACTIONED,
        NEEDS_INFO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "post_id")
    private Long postId;

    @Column(name = "reporter_email", nullable = false, length = 255)
    private String reporterEmail;

    @Column(name = "target_author_email", length = 255)
    private String targetAuthorEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Reason reason;

    @Column(length = 1000)
    private String details;

    @Column(name = "content_preview", length = 1000)
    private String contentPreview;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(name = "reviewer_email", length = 255)
    private String reviewerEmail;

    @Column(name = "reviewer_notes", length = 1000)
    private String reviewerNotes;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = ReviewStatus.PENDING;
    }
}
