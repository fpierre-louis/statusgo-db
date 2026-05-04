package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Polymorphic vote on an Ask-surface entity (question / answer / tip).
 * One row per (target, voter); the {@link #value} flips between +1 and -1
 * when a user changes their mind. Removing a vote deletes the row.
 *
 * <p>Denormalized {@code vote_score} on the parent entity is updated in
 * the same transaction as the vote insert/update/delete, so no aggregate
 * query is needed at read time.</p>
 *
 * <p>Polymorphism via {@code targetType} string + {@code targetId} (rather
 * than a join table per type) keeps the schema flat and lets a single
 * "my votes" query power the FE's vote-state hydration in one round trip.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "ask_vote",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ask_vote_target_voter",
                columnNames = { "target_type", "target_id", "voter_email" }
        ),
        indexes = {
                @Index(name = "idx_ask_vote_target", columnList = "target_type,target_id"),
                @Index(name = "idx_ask_vote_voter", columnList = "voter_email")
        }
)
public class AskVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "question" | "answer" | "tip". Lowercase, validated at the service layer. */
    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "voter_email", nullable = false, length = 320)
    private String voterEmail;

    /** +1 = upvote, -1 = downvote. Zero is not stored — the row is deleted instead. */
    @Column(nullable = false)
    private int value;

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
