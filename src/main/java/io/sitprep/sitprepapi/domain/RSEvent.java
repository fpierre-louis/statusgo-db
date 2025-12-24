// src/main/java/io/sitprep/sitprepapi/domain/RSEvent.java
package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "rs_events",
        indexes = {
                @Index(name = "idx_rs_events_group", columnList = "rs_group_id"),
                @Index(name = "idx_rs_events_starts", columnList = "starts_at"),
                @Index(name = "idx_rs_events_public_starts", columnList = "is_public,starts_at"),
                @Index(name = "idx_rs_events_visibility", columnList = "visibility"),
                @Index(name = "idx_rs_events_cancelled", columnList = "is_cancelled")
        }
)
public class RSEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "rs_event_id", unique = true, updatable = false)
    private String id;

    // ---------------- Core Event Fields ----------------

    // RSEvent.java
    @Column(name = "rs_group_id", nullable = true)
    private String groupId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "location_name")
    private String locationName;

    @Column(name = "address")
    private String address;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    // ---------------- Recurrence ----------------

    @Column(name = "recurrence_rule")
    private String recurrenceRule;

    @Column(name = "recurrence_timezone")
    private String recurrenceTimezone;

    @Column(name = "series_id")
    private String seriesId;

    @Column(name = "is_exception", nullable = false)
    private boolean isException = false;

    @Column(name = "original_starts_at")
    private Instant originalStartsAt;

    // ---------------- Visibility ----------------

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility")
    private RSEventVisibility visibility;

    @Column(name = "is_private", nullable = false)
    private Boolean isPrivate;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    // ---------------- Join rules ----------------

    @Column(name = "max_attendees")
    private Integer maxAttendees;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval = false;

    @Column(name = "is_free", nullable = false)
    private boolean isFree = true;

    @Column(name = "cost_cents")
    private Integer costCents;

    @Column(name = "currency")
    private String currency;

    @Column(name = "skill_level")
    private String skillLevel;

    @Column(name = "is_indoor")
    private Boolean isIndoor;

    // ---------------- Creator ----------------

    @Column(name = "created_by_email", nullable = false)
    private String createdByEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by_email",
            referencedColumnName = "user_email",
            insertable = false,
            updatable = false
    )
    private UserInfo createdByUserInfo;

    /**
     * Legacy attendee storage (keep temporarily).
     * Canonical source is rs_event_attendance.
     */
    @Deprecated
    @ElementCollection
    @CollectionTable(
            name = "rs_event_attendees",
            joinColumns = @JoinColumn(name = "rs_event_id")
    )
    @Column(name = "attendee_email")
    @Setter(AccessLevel.NONE) // ✅ prevent accidental reassignment (Hibernate-safe)
    private final Set<String> attendeeEmails = new HashSet<>();

    // ✅ safe mutator: never reassign the collection instance
    public void setAttendeeEmails(Set<String> incoming) {
        attendeeEmails.clear();
        if (incoming == null || incoming.isEmpty()) return;

        for (String e : incoming) {
            if (e != null && !e.isBlank()) attendeeEmails.add(e.trim().toLowerCase());
        }
    }

    // ---------------- Meta ----------------

    @Column(name = "notes", length = 4000)
    private String notes;

    @Column(name = "is_cancelled", nullable = false)
    private boolean isCancelled = false;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by_email")
    private String cancelledByEmail;

    @Column(name = "cancel_reason", length = 1000)
    private String cancelReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    void prePersist() {
        final Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;

        if (isPrivate == null) isPrivate = false;

        ensureVisibilityAndSyncFlags();
        normalizeEmails();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        ensureVisibilityAndSyncFlags();
        normalizeEmails();
    }

    private void ensureVisibilityAndSyncFlags() {
        if (visibility == null) {
            if (Boolean.TRUE.equals(isPrivate)) {
                visibility = RSEventVisibility.INVITE_ONLY;
            } else if (isPublic) {
                visibility = RSEventVisibility.DISCOVERABLE_PUBLIC;
            } else {
                visibility = RSEventVisibility.GROUP_ONLY;
            }
        }

        switch (visibility) {
            case DISCOVERABLE_PUBLIC -> {
                isPublic = true;
                isPrivate = false;
            }
            case GROUP_ONLY -> {
                isPublic = false;
                isPrivate = false;
            }
            case INVITE_ONLY -> {
                isPublic = false;
                isPrivate = true;
            }
        }
    }

    private void normalizeEmails() {
        if (createdByEmail != null) createdByEmail = createdByEmail.trim().toLowerCase();
        if (cancelledByEmail != null) cancelledByEmail = cancelledByEmail.trim().toLowerCase();

        // ✅ never replace the collection instance (Hibernate PersistentCollection safe)
        if (!attendeeEmails.isEmpty()) {
            Set<String> normalized = new HashSet<>();
            for (String e : attendeeEmails) {
                if (e != null && !e.isBlank()) normalized.add(e.trim().toLowerCase());
            }
            attendeeEmails.clear();
            attendeeEmails.addAll(normalized);
        }
    }
}