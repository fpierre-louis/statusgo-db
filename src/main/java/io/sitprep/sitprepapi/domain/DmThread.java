package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Direct-message thread — exactly one row per unordered pair of users.
 *
 * <p>Emails are the identity key, same convention as {@link Follow} and
 * {@link Block}. The service layer stores them lowercase AND in
 * lexicographic order ({@code participantAEmail < participantBEmail}),
 * so the unique constraint guarantees a single thread per pair no
 * matter which side sends first.</p>
 *
 * <p>Per-participant read watermarks live here rather than on a join
 * table: a 1:1 thread has exactly two readers, so two nullable columns
 * beat an extra entity. {@code aLastReadAt} belongs to whichever user
 * occupies the A column.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "dm_thread",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_dm_thread_participants",
                        columnNames = {"participant_a_email", "participant_b_email"}
                )
        },
        indexes = {
                @Index(name = "idx_dm_thread_a", columnList = "participant_a_email"),
                @Index(name = "idx_dm_thread_b", columnList = "participant_b_email")
        }
)
public class DmThread {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lexicographically smaller participant email. Lowercase. */
    @Column(name = "participant_a_email", nullable = false, length = 255)
    private String participantAEmail;

    /** Lexicographically larger participant email. Lowercase. */
    @Column(name = "participant_b_email", nullable = false, length = 255)
    private String participantBEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Denormalized newest-message stamp — drives inbox ordering. */
    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    /** Read watermark for the participant in the A column. */
    @Column(name = "a_last_read_at")
    private Instant aLastReadAt;

    /** Read watermark for the participant in the B column. */
    @Column(name = "b_last_read_at")
    private Instant bLastReadAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
