package io.sitprep.sitprepapi.gamification;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/** One member having seen one household award (V94). */
@Entity
@Getter
@Setter
@Table(name = "household_token_seen")
public class HouseholdTokenSeen {

    @EmbeddedId
    private Key id;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "award_id", nullable = false)
        private Long awardId;

        @Column(name = "user_email", nullable = false, length = 320)
        private String userEmail;
    }
}
