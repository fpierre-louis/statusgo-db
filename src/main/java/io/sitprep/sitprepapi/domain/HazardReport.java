package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The hazard specifics of a {@code kind='hazard'} post (V87). One row per
 * post, keyed by the post id. The post itself (title = category label, note,
 * location, photos, author) lives in {@code task} through {@link Post}; this
 * table holds only what the map and the router need, so the post table and
 * its service stay untouched.
 *
 * <p>No reporter here and none in any DTO built from it — the author is on
 * the post row and never leaves the server on the hazard read path.</p>
 */
@Entity
@Table(name = "hazard_report", indexes = @Index(name = "idx_hazard_report_expires", columnList = "expires_at"))
@Getter
@Setter
public class HazardReport {

    /** The post's id — {@code task.id}. */
    @Id
    @Column(name = "task_id")
    private Long taskId;

    /** {@link io.sitprep.sitprepapi.constant.HazardCategory} wire key. */
    @Column(name = "category", nullable = false, length = 24)
    private String category;

    @Column(name = "radius_m", nullable = false)
    private int radiusM;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    /** Pushed out by each "still there", capped at 3× the category lifetime. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** A report with a photo counts as Confirmed on its own (H-2). */
    @Column(name = "has_photo", nullable = false)
    private boolean hasPhoto;

    @Column(name = "official_at")
    private Instant officialAt;

    /** The agency group id that marked it Official. */
    @Column(name = "official_by", length = 160)
    private String officialBy;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    /** The agency group id that cleared it. */
    @Column(name = "cleared_by", length = 160)
    private String clearedBy;
}
