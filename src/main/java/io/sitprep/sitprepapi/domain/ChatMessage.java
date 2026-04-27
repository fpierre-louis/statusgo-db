package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A single chat-style message within a group (household-flavored Feed today,
 * extensible to other intimate group surfaces). Kept deliberately lean
 * compared to {@link Post}: no tags / mentions / reactions / image — if the
 * household Feed needs those later, they go on top, not in the hot path.
 */
@Entity
@Getter
@Setter
@Table(
        name = "chat_message",
        indexes = {
                @Index(name = "idx_chat_group_created", columnList = "group_id,created_at")
        }
)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    @Column(name = "author_email", nullable = false)
    private String authorEmail;

    @Column(length = 4096)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Last user-initiated edit. Null if never edited. */
    @Column(name = "edited_at")
    private Instant editedAt;

    /**
     * Any modification timestamp (create/edit/soft-delete). Drives {@code since}
     * backfill after a reconnect so clients can pull incremental changes.
     */
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
