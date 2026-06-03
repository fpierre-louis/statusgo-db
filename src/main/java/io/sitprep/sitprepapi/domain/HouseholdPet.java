package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Named household pet. Pets used to exist only as demographic counts, which
 * made contact assignment ("vet for which pet?") falsely specific. This row
 * gives pets stable IDs without making them app members or map participants.
 */
@Entity
@Table(
        name = "household_pet",
        indexes = @Index(name = "idx_household_pet_household", columnList = "household_id")
)
@Getter
@Setter
public class HouseholdPet {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "household_id", nullable = false, length = 64)
    private String householdId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 40)
    private String species;

    @Column(length = 1024)
    private String notes;

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
