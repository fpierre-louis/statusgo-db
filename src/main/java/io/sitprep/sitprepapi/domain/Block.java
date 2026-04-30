package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Block edge — one row per (blocker → blocked) pair. Per
 * {@code docs/PROFILE_AND_FOLLOW.md}: block is the safety primitive
 * that trumps everything else. A blocked user sees the blocker as
 * 404 (profile doesn't exist) and the blocker doesn't see the blocked
 * user's posts in their community feed.
 *
 * <p>Symmetric storage: only the {@code blocker} side has a row, but
 * the service-layer privacy check tests both directions ("did A
 * block B" OR "did B block A") so either party hides the other. This
 * matches how Twitter, Instagram, Facebook all handle blocks at the
 * data layer.</p>
 *
 * <p>Email-keyed and lowercase-normalized at the service layer so the
 * unique constraint catches case-only duplicates without a generated
 * column. Mirrors the {@link Follow} entity's design.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "block",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_block_blocker_blocked",
                        columnNames = {"blocker_email", "blocked_email"}
                )
        },
        indexes = {
                @Index(name = "idx_block_blocker", columnList = "blocker_email"),
                @Index(name = "idx_block_blocked", columnList = "blocked_email")
        }
)
public class Block {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blocker_email", nullable = false, length = 255)
    private String blockerEmail;

    @Column(name = "blocked_email", nullable = false, length = 255)
    private String blockedEmail;

    @Column(name = "since", nullable = false)
    private Instant since;

    @PrePersist
    void onCreate() {
        if (since == null) since = Instant.now();
    }
}
