package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A first-class "Confirm" on a {@link Post} (community-feed post) — the
 * "I'm prepared" / "Me too" / "I see it too" signal on official + civic
 * cards. One row per (task, user) so it's idempotent and the count is a
 * straight {@code COUNT(*)}.
 *
 * <p>Deliberately a SEPARATE primitive from {@link PostReaction} (the
 * heart "Thank") rather than a reserved emoji token, so a confirm is a
 * distinct, queryable corroboration signal — decided 2026-06-15, see
 * {@code docs/design_handoff_community/backend/CONTRACT.md}. Shape mirrors
 * {@link PostReaction} (modulo the emoji column) so the count/viewer-state
 * folding in {@code PostConfirmService} matches {@code PostReactionService}.</p>
 */
@Entity
@Table(
        name = "post_confirm",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_post_confirm_task_user",
                columnNames = { "task_id", "user_email" }
        ),
        indexes = {
                @Index(name = "idx_post_confirm_task", columnList = "task_id"),
                @Index(name = "idx_post_confirm_user", columnList = "user_email")
        }
)
@Getter
@Setter
public class PostConfirm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long postId;

    @Column(name = "user_email", nullable = false, length = 320)
    private String userEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
