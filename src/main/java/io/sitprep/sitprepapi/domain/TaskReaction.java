package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-user emoji reaction on a {@link Task} (community-feed post). One row
 * per (task, user, emoji) so toggling is exactly add-or-remove and the
 * listing path can return the full roster of who reacted with what.
 *
 * <p>Mirrors {@link GroupPostReaction} exactly — same column names (modulo
 * post_id → task_id), same uniqueness constraint shape, same indexes —
 * so the eventual GroupPost/Task entity merge (telegraphed in
 * {@code TaskDto}'s class doc) collapses both reaction tables into one
 * with minimal migration effort.</p>
 *
 * <p>The "Thank" affordance on community feed cards toggles the heart
 * emoji ({@code "❤"}); the table supports the full emoji vocabulary
 * so future surfaces (multi-emoji picker, sticker reactions) reuse the
 * same shape without schema work.</p>
 */
@Entity
@Table(
        name = "task_reaction",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_task_reaction_task_user_emoji",
                columnNames = { "task_id", "user_email", "emoji" }
        ),
        indexes = {
                @Index(name = "idx_task_reaction_task", columnList = "task_id"),
                @Index(name = "idx_task_reaction_user", columnList = "user_email")
        }
)
@Getter
@Setter
public class TaskReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(nullable = false, length = 32)
    private String emoji;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @PrePersist
    void onCreate() {
        if (addedAt == null) addedAt = Instant.now();
    }
}
