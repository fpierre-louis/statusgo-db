package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Junction record: one row per "we created an auto-post for this alert in
 * this geocell". Implements the dedup rule from
 * {@code docs/ALERTS_INTEGRATION.md}: exactly one auto-post per alert per
 * radius, regardless of how many users in the cell are subscribed. The
 * (alertId, geocellId) unique constraint enforces that at the DB level so
 * a racing dispatch tick can't double-create.
 *
 * <p>The actual community post lives in {@link Post} — this row is the
 * tracking record that lets the resolve path find the right post to
 * mark expired when the upstream alert ends, and lets the dispatch tick
 * skip alerts already posted.</p>
 *
 * <p>Created by {@code AlertDispatchService} when the dispatch tick
 * intersects an active alert with a populated geocell. Cleaned up by
 * the resolve tick when the alert ends (sets {@code resolvedAt} +
 * marks the parent {@link Post} for visual demotion).</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "alert_post",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_alert_post_dedup",
                columnNames = {"alert_id", "geocell_id"}
        ),
        indexes = {
                @Index(name = "idx_alert_post_alert", columnList = "alert_id"),
                @Index(name = "idx_alert_post_unresolved",
                        columnList = "resolved_at,alert_id")
        }
)
public class AlertPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Composite source+id, e.g. {@code "nws-X12345"} or
     * {@code "usgs-us6000abcd"}. Stable across dispatch ticks so dedup
     * works even when the same alert appears in multiple ingest snapshots.
     */
    @Column(name = "alert_id", nullable = false, length = 128)
    private String alertId;

    /**
     * Hazard family for filter / template selection: hurricane, wildfire,
     * earthquake, flood, blizzard, heat, aqi, other. Lowercase, free-form
     * for forward compat.
     */
    @Column(name = "hazard_type", length = 32)
    private String hazardType;

    /**
     * Geographic cell identifier — bucket of (lat, lng). Implementation
     * detail of the geocell helper: zip-bucket prefix for v1, switchable
     * to H3 hexes when scale demands.
     */
    @Column(name = "geocell_id", nullable = false, length = 32)
    private String geocellId;

    /**
     * FK to the {@link Post} created for this auto-post. Stored as the
     * post's id rather than a JPA association so the resolve path can
     * touch the Post via the existing {@code PostRepo} without a
     * cascade-managed lifecycle.
     */
    @Column(name = "post_id", nullable = false)
    private Long postId;

    /** Wall-clock when the auto-post was created. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Mirrors the upstream alert's expiry. Used by the resolve tick as
     * one of two signals (the other is upstream alert disappearing from
     * the active set entirely). Null until the alert source publishes
     * an end time.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * Wall-clock when the resolve tick marked the parent post as
     * cleared. Drives visual demotion on the FE feed (greyed
     * background, "This alert has cleared" footer per the spec).
     * Indexed alongside {@code alertId} so the dispatch tick can
     * cheap-filter to "active rows for this alert".
     */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
