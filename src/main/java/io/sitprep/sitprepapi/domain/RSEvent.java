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
@Table(name = "rs_events")
public class RSEvent {

    @Id
    @UuidGenerator
    @Column(name = "rs_event_id", unique = true, updatable = false)
    private String id;

    // ---------------- Core Event Fields ----------------

    @Column(name = "rs_group_id", nullable = false)
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
    private String latitude;

    @Column(name = "longitude")
    private String longitude;

    @Column(name = "recurrence_rule")
    private String recurrenceRule;

    // ---------------- Visibility ----------------

    /**
     * Nullable on purpose:
     * - null  → field not sent by client
     * - true  → explicitly private
     * - false → explicitly public-to-group
     */
    @Column(name = "is_private", nullable = false)
    private Boolean isPrivate;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    // ---------------- Creator / Attendance ----------------

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

    @ElementCollection
    @CollectionTable(
            name = "rs_event_attendees",
            joinColumns = @JoinColumn(name = "rs_event_id")
    )
    @Column(name = "attendee_email")
    private Set<String> attendeeEmails = new HashSet<>();

    // ---------------- Meta ----------------

    @Column(name = "notes", length = 4000)
    private String notes;

    @Column(name = "is_cancelled", nullable = false)
    private boolean isCancelled = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ---------------- Lifecycle ----------------

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (isPrivate == null) isPrivate = false; // ✅ default ONLY on create
        normalizeEmails();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        normalizeEmails();
    }

    private void normalizeEmails() {
        if (createdByEmail != null) {
            createdByEmail = createdByEmail.trim().toLowerCase();
        }

        if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
            Set<String> normalized = new HashSet<>();
            for (String e : attendeeEmails) {
                if (e != null && !e.isBlank()) {
                    normalized.add(e.trim().toLowerCase());
                }
            }
            attendeeEmails = normalized;
        }
    }
}