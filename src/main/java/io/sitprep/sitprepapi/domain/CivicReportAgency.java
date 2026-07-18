package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Civic epic Slice 2 (V53) — one row per (civic report, tagged agency). Turns
 * the report's single {@code tagged_agency_group_id} into a many-to-many and
 * carries the per-tag claim lifecycle.
 *
 * <ul>
 *   <li>{@code tagSource} — how the tag arose: {@code auto} (resolver-derived at
 *       create), {@code citizen_added} (filer added an authorized agency), or
 *       {@code legacy} (V53 backfill of the old single tag).</li>
 *   <li>{@code active} — false is a tombstone: the resolver auto-tagged this
 *       agency but the filer deselected it (decision 1). Kept, not deleted.</li>
 *   <li>{@code claimed} — this agency holds the active claim. At most one claimed
 *       row per report, enforced by the Postgres partial-unique index
 *       {@code uk_civic_report_one_claim} (V53) AND a service guard (the index is
 *       not expressible in JPA, so the H2 test profile relies on the guard).</li>
 * </ul>
 *
 * The FK {@code post_id → task(id) ON DELETE CASCADE} lives in the migration
 * (Postgres-only, like V50's constraints); this entity uses a plain {@code Long
 * postId} so {@code ddl-auto=validate} + the H2 create-drop profile both work.
 */
@Entity
@Getter
@Setter
@Table(
        name = "civic_report_agency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cra_post_agency", columnNames = {"post_id", "agency_group_id"}),
        indexes = {
                @Index(name = "idx_cra_post", columnList = "post_id"),
                @Index(name = "idx_cra_agency_claimed", columnList = "agency_group_id,claimed")
        })
public class CivicReportAgency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "agency_group_id", nullable = false, length = 64)
    private String agencyGroupId;

    /** auto | citizen_added | legacy */
    @Column(name = "tag_source", nullable = false, length = 16)
    private String tagSource;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "claimed", nullable = false)
    private boolean claimed = false;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claimed_by_email", length = 320)
    private String claimedByEmail;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
