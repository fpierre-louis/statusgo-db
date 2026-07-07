package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One direct message inside a {@link DmThread}. Plain {@code threadId}
 * column instead of a JPA relation — matches the flat-column style of
 * {@code HouseholdEvent} / {@code GroupPostComment} and keeps the
 * hot-path list query free of join fetch tuning.
 */
@Entity
@Getter
@Setter
@Table(
        name = "dm_message",
        indexes = {
                @Index(
                        name = "idx_dm_message_thread_created",
                        columnList = "thread_id, created_at"
                )
        }
)
public class DmMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "thread_id", nullable = false)
    private Long threadId;

    /** Sender identity email. Lowercase. */
    @Column(name = "sender_email", nullable = false, length = 255)
    private String senderEmail;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
