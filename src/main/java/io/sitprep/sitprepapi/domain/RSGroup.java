// src/main/java/io/sitprep/sitprepapi/domain/RSGroup.java
package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "rs_groups",
        indexes = {
                @Index(name = "idx_rs_groups_private", columnList = "is_private"),
                @Index(name = "idx_rs_groups_sport", columnList = "sport_type"),
                @Index(name = "idx_rs_groups_owner", columnList = "owner_email"),

                // ✅ new indexes for discovery & policy
                @Index(name = "idx_rs_groups_discoverable", columnList = "is_discoverable"),
                @Index(name = "idx_rs_groups_allow_public_events", columnList = "allow_public_events"),
                @Index(name = "idx_rs_groups_default_visibility", columnList = "default_event_visibility")
        }
)
public class RSGroup {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "rs_group_id", unique = true, updatable = false, nullable = false)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    // Examples: "Basketball", "Soccer", "Running", "Pickleball"
    @Column(name = "sport_type", nullable = false)
    private String sportType;

    @Column(name = "description", length = 2000)
    private String description;

    /**
     * Private group means membership required to view group content.
     * Public group means anyone can join (your existing semantics).
     */
    @Column(name = "is_private", nullable = false)
    private boolean isPrivate = false;

    /**
     * ✅ NEW: Controls whether this group appears in group discovery/search.
     * - true  => group can show up when browsing groups
     * - false => group only accessible via direct link/invite (even if not private)
     *
     * Default true for public groups; false for private groups (set in prePersist).
     */
    @Column(name = "is_discoverable", nullable = false)
    private boolean isDiscoverable = true;

    /**
     * ✅ NEW: Controls whether events in this group are allowed to be DISCOVERABLE_PUBLIC.
     * - If false, group events can only be GROUP_ONLY or INVITE_ONLY.
     *
     * Default:
     * - public group => true
     * - private group => false
     */
    @Column(name = "allow_public_events", nullable = false)
    private boolean allowPublicEvents = true;

    /**
     * ✅ NEW: Default event visibility used when creating an event (if not explicitly set by client).
     * Helps frontend + backend behave consistently.
     *
     * Default:
     * - public group => GROUP_ONLY (safer default) OR DISCOVERABLE_PUBLIC (growth default)
     * - private group => GROUP_ONLY
     *
     * We'll choose GROUP_ONLY by default to prevent accidental public posting.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_event_visibility", nullable = false)
    private RSEventVisibility defaultEventVisibility = RSEventVisibility.GROUP_ONLY;

    @Column(name = "owner_email", nullable = false)
    private String ownerEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        final Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;

        if (ownerEmail != null) ownerEmail = ownerEmail.trim().toLowerCase();

        // ✅ Policy defaults derived from private/public
        // - private groups: not discoverable, no public events
        if (isPrivate) {
            isDiscoverable = false;
            allowPublicEvents = false;

            if (defaultEventVisibility == null) {
                defaultEventVisibility = RSEventVisibility.GROUP_ONLY;
            }
        } else {
            // public groups: discoverable by default
            // keep allowPublicEvents true unless explicitly set otherwise
            if (defaultEventVisibility == null) {
                defaultEventVisibility = RSEventVisibility.GROUP_ONLY;
            }
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        if (ownerEmail != null) ownerEmail = ownerEmail.trim().toLowerCase();

        // ✅ If group switches to private, enforce policy unless you explicitly override
        if (isPrivate) {
            // It's safer to force these when private
            isDiscoverable = false;
            allowPublicEvents = false;

            if (defaultEventVisibility == null) {
                defaultEventVisibility = RSEventVisibility.GROUP_ONLY;
            }
        } else {
            if (defaultEventVisibility == null) {
                defaultEventVisibility = RSEventVisibility.GROUP_ONLY;
            }
        }
    }
}