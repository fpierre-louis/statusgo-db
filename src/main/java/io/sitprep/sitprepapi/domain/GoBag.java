package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One physical go bag (72-hour evacuation kit) — a container with a
 * storage location, owned by a household. A household can have several
 * (primary home bag, vehicle bag, work bag).
 *
 * <p>Plan-sibling conventions (matches {@link EvacuationPlan} /
 * {@link MeetingPlace}): dual-keyed {@code ownerEmail} + {@code householdId},
 * bare {@code Double lat}/{@code lng} scalar columns (NOT the
 * {@link GeoPoint} embeddable — that's for user/group identity entities).
 * The id is supplied client-side (UUIDs) so an offline create can
 * reference the new bag immediately and reconcile on sync (same pattern
 * as {@link HouseholdManualMember}).</p>
 *
 * <p>PRIVACY: a bag's storage location is a map of what's worth stealing
 * in a house. GoBag data ships ONLY on household-member-gated endpoints —
 * never through community discovery, {@code map-public}, or any
 * unauthenticated surface. See docs/features/GO_BAG_WIZARD_SPEC.md §7.6.</p>
 */
@Entity
@Table(
        name = "go_bag",
        indexes = @Index(name = "idx_go_bag_household", columnList = "household_id")
)
@Getter
@Setter
public class GoBag {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "household_id", nullable = false, length = 64)
    private String householdId;

    /** Creator — audit/migration parity with the other plan siblings. */
    @Column(name = "owner_email", length = 255)
    private String ownerEmail;

    @Column(nullable = false, length = 120)
    private String name;

    /** home | vehicle | work | other */
    @Column(nullable = false, length = 24)
    private String kind;

    /** The exact spot: "Hallway closet by the front door", "Honda Civic trunk". */
    @Column(name = "storage_label", length = 255)
    private String storageLabel;

    /**
     * Optional pin. Home bags default to the household's home coords;
     * vehicle bags stay null (vehicles move — their identity is the label).
     */
    private Double lat;
    private Double lng;

    /** premade | diy | hybrid — how the household sources contents. */
    @Column(length = 24)
    private String strategy;

    /** Which base kit they bought, when strategy is premade/hybrid. */
    @Column(name = "premade_kit_label", length = 255)
    private String premadeKitLabel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
