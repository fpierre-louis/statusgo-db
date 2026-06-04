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
        name = "publisher_publish_audit",
        indexes = {
                @Index(name = "idx_pub_audit_created", columnList = "created_at"),
                @Index(name = "idx_pub_audit_actor", columnList = "actor_email"),
                @Index(name = "idx_pub_audit_org", columnList = "organization_id"),
                @Index(name = "idx_pub_audit_post", columnList = "post_table,post_id")
        }
)
public class PublisherPublishAudit {

    public enum ReviewStatus {
        PENDING,
        APPROVED,
        REJECTED,
        NEEDS_INFO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(name = "publisher_email", length = 255)
    private String publisherEmail;

    @Column(name = "organization_id", length = 80)
    private String organizationId;

    @Column(name = "organization_name", length = 255)
    private String organizationName;

    @Column(name = "organization_kind", length = 80)
    private String organizationKind;

    @Column(name = "reach_label", length = 400)
    private String reachLabel;

    @Column(name = "permanent_address", length = 400)
    private String permanentAddress;

    @Column(name = "temporary_event_address", length = 400)
    private String temporaryEventAddress;

    private Double latitude;
    private Double longitude;

    @Column(name = "post_table", nullable = false, length = 32)
    private String postTable;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 24)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

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
        if (reviewStatus == null) reviewStatus = ReviewStatus.PENDING;
    }
}
