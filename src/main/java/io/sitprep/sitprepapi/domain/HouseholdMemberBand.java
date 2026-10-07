package io.sitprep.sitprepapi.domain;

import io.sitprep.sitprepapi.constant.HouseholdBand;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * The band an ACCOUNT holds in one household (V100). Absent row → ADULT, the
 * default for every account (ToS 18+ attestation). A row exists when an
 * account claimed a manual spot: the slot keeps the band it was counted in,
 * so a teenager who claims "Maya · Teen" stays a teen on the plan.
 */
@Entity
@Table(name = "household_member_band")
@IdClass(HouseholdMemberBand.Key.class)
@Getter
@Setter
public class HouseholdMemberBand {

    @Id
    @Column(name = "household_id", nullable = false, length = 255)
    private String householdId;

    /** Lower-cased (DB CHECK). */
    @Id
    @Column(name = "user_email", nullable = false, length = 320)
    private String userEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "band", nullable = false, length = 8)
    private HouseholdBand band;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public static class Key implements Serializable {
        private String householdId;
        private String userEmail;

        public Key() {}

        public Key(String householdId, String userEmail) {
            this.householdId = householdId;
            this.userEmail = userEmail;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(householdId, k.householdId) && Objects.equals(userEmail, k.userEmail);
        }

        @Override public int hashCode() {
            return Objects.hash(householdId, userEmail);
        }
    }
}
