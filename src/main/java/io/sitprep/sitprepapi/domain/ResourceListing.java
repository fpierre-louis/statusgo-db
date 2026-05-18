package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One entry on the community resource board — a place or service that
 * helps people prepare for or get through an emergency: a cooling
 * center, an evacuation shelter, a food bank, a CPR class, a hotline.
 *
 * <p>Phase 2 of docs/BUSINESS_MODEL.md ("community feed retention").
 * Three sources feed the same board:</p>
 * <ul>
 *   <li>{@code COMMUNITY} — submitted by residents on the ground.</li>
 *   <li>{@code OFFICIAL}  — SitPrep-seeded national lines today;
 *       verified cities / orgs posting jurisdiction resources later
 *       (the Phase 5 agency hook).</li>
 *   <li>{@code EXTERNAL}  — imported from a government / NGO feed. The
 *       enum slot exists now; importers are a later addition.</li>
 * </ul>
 *
 * <p>A listing with {@code latitude/longitude} is geo-pinned and shown
 * within range of the viewer; a listing with null coords is national
 * (a hotline) and always shown. {@code sourceKey} is the stable natural
 * key for seeded / imported rows so re-runs upsert rather than
 * duplicate — community submissions leave it null.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "resource_listing",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_resource_listing_source_key",
                columnNames = "source_key"
        ),
        indexes = {
                @Index(name = "idx_resource_listing_status", columnList = "status"),
                @Index(name = "idx_resource_listing_coords",
                        columnList = "latitude,longitude")
        }
)
public class ResourceListing {

    /** Who put the listing on the board. */
    public enum Source { COMMUNITY, OFFICIAL, EXTERNAL }

    /**
     * Moderation state. Community submissions auto-APPROVE in closed
     * beta; the PENDING / REJECTED states are here for when the board
     * opens to the public and needs a review queue.
     */
    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 140)
    private String title;

    @Column(length = 1000)
    private String description;

    /**
     * Free-form lowercase category — cooling-center, warming-center,
     * shelter, food, water, medical, charging, supplies, hotline,
     * other. Free-form (not an enum) for forward compatibility, the
     * same choice as {@code AlertPost.hazardType}.
     */
    @Column(length = 40)
    private String category;

    /** Geo anchor. Null on both → a national listing, always shown. */
    private Double latitude;
    private Double longitude;

    /** Human-readable place text — an address or a landmark. */
    @Column(length = 240)
    private String address;

    /** Optional action target — a tel: number or an http(s) URL. */
    @Column(length = 400)
    private String contact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source = Source.COMMUNITY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.APPROVED;

    /**
     * Stable natural key for seeded / imported rows (e.g.
     * {@code "official:211"}). Null for community submissions, so the
     * unique constraint never blocks two resident submissions.
     */
    @Column(name = "source_key", length = 120)
    private String sourceKey;

    /** Email of the resident who submitted a COMMUNITY listing. */
    @Column(length = 160)
    private String submittedByEmail;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (source == null) source = Source.COMMUNITY;
        if (status == null) status = Status.APPROVED;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
