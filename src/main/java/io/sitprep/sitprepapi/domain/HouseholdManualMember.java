package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * "Manual" household member — children, elders, anyone in the family who
 * doesn't have an account on the platform. They appear in the roster, can
 * be claimed via the with-me feature, and can be marked safe/help by the
 * supervisor on their behalf, but they don't sign in or post.
 *
 * <p>The id is supplied client-side (UUIDs) so an offline create can
 * reference the new member immediately and reconcile on sync.</p>
 */
@Entity
@Table(
        name = "household_manual_member",
        indexes = @Index(name = "idx_manual_member_household", columnList = "household_id")
)
@Getter
@Setter
public class HouseholdManualMember {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "household_id", nullable = false, length = 64)
    private String householdId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 120)
    private String relationship;

    private Integer age;

    /**
     * Whether this manual member is 18 or older. Defaults to {@code false}
     * (treated as a minor). Locked 2026-06-02 per docs/MAP_SURFACES_REDESIGN_PLAN.md
     * Phase 4: manual members are minors by default — the operating account
     * holder attests to adulthood via the ToS at signup, but added family
     * members do not. An admin can flip this on per-row for the rare elder-
     * without-phone case who wants to be tracked on a non-household group's
     * map. Minors never appear on non-household group maps regardless of
     * any per-group sharing setting.
     */
    // columnDefinition supplies a database-level default so Hibernate's
    // auto-DDL can add this NOT NULL column to existing non-empty tables
    // (pre-existing rows otherwise can't satisfy the NOT NULL constraint).
    @Column(name = "is_adult", nullable = false, columnDefinition = "boolean default false not null")
    private Boolean isAdult = Boolean.FALSE;

    /** R2 object key or full URL — same convention as profile photos. */
    @Column(name = "photo_url", length = 1024)
    private String photoUrl;

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
