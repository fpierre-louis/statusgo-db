// src/main/java/io/sitprep/sitprepapi/domain/RSEventAttendance.java
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
@Builder
@Table(
        name = "rs_event_attendance",
        uniqueConstraints = @UniqueConstraint(columnNames = {"rs_event_id", "attendee_email"}),
        indexes = {
                @Index(name = "idx_rs_att_event", columnList = "rs_event_id"),
                @Index(name = "idx_rs_att_email", columnList = "attendee_email"),
                @Index(name = "idx_rs_att_status", columnList = "status")
        }
)
public class RSEventAttendance {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "rs_event_attendance_id", unique = true, updatable = false, nullable = false)
    private String id;

    @Column(name = "rs_event_id", nullable = false)
    private String eventId;

    @Column(name = "attendee_email", nullable = false)
    private String attendeeEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RSAttendanceStatus status = RSAttendanceStatus.GOING;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private RSEventAttendeeRole role = RSEventAttendeeRole.PLAYER;

    @Column(name = "guest_count", nullable = false)
    private int guestCount = 0;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ---- READ-ONLY join to user_info by email (same pattern you use elsewhere) ----
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "attendee_email",
            referencedColumnName = "user_email",
            insertable = false,
            updatable = false
    )
    private UserInfo attendeeUserInfo;

    @PrePersist
    void prePersist() {
        final Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (joinedAt == null) joinedAt = now;
        normalizeEmails();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        normalizeEmails();
    }

    private void normalizeEmails() {
        if (attendeeEmail != null) attendeeEmail = attendeeEmail.trim().toLowerCase();
    }
}