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
                @Index(name = "idx_rs_groups_owner", columnList = "owner_email")
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

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate = false;

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
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        if (ownerEmail != null) ownerEmail = ownerEmail.trim().toLowerCase();
    }
}