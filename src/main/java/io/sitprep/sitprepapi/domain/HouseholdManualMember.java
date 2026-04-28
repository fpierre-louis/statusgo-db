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
