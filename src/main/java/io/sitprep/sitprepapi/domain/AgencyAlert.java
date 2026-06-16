package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A geo-targeted alert sent by a verified agency group (Phase 5 Slice D) —
 * also the audit + idempotency record.
 *
 * <p>The unique {@code dedup_key} is the <b>double-send guard</b>: a
 * duplicate city-wide push is trust-destroying, so a second send with the
 * same client idempotency key (or the same content inside a 10-minute
 * window) collides on the constraint and is rejected before re-dispatch —
 * the same {@code saveAndFlush}+catch pattern used by PostConfirm. {@code
 * recipientCount}/{@code postId} record what actually went out.</p>
 */
@Entity
@Table(
        name = "agency_alert",
        uniqueConstraints = @UniqueConstraint(name = "uk_agency_alert_dedup", columnNames = {"dedup_key"}),
        indexes = {
                @Index(name = "idx_agency_alert_group", columnList = "publisher_group_id,created_at")
        }
)
@Getter
@Setter
public class AgencyAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "publisher_group_id", nullable = false, length = 80)
    private String publisherGroupId;

    @Column(name = "dedup_key", nullable = false, length = 160)
    private String dedupKey;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "body", length = 2000)
    private String body;

    @Column(name = "official_tier", length = 16)
    private String officialTier;

    /** Comma-joined target zips (audit only; recipients query user_info). */
    @Column(name = "affected_zips", length = 2000)
    private String affectedZips;

    @Column(name = "post_id")
    private Long postId;

    @Column(name = "recipient_count")
    private Integer recipientCount;

    @Column(name = "created_by", length = 320)
    private String createdBy;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
