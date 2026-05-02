package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-user emoji reaction on a {@link GroupPostComment} (group chat
 * comment). Mirrors {@link PostCommentReaction} (community-feed comment
 * reactions) modulo the FK target — same column shape, same uniqueness
 * constraint, same indexes.
 *
 * <p>The "Thank" affordance on group chat comment bubbles toggles the
 * heart emoji ({@code "❤"}); the table supports the full emoji
 * vocabulary so a future multi-emoji picker reuses the same shape
 * without schema work.</p>
 */
@Entity
@Table(
        name = "group_post_comment_reaction",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_group_post_comment_reaction_comment_user_emoji",
                columnNames = { "group_post_comment_id", "user_email", "emoji" }
        ),
        indexes = {
                @Index(name = "idx_group_post_comment_reaction_comment", columnList = "group_post_comment_id"),
                @Index(name = "idx_group_post_comment_reaction_user", columnList = "user_email")
        }
)
@Getter
@Setter
public class GroupPostCommentReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_post_comment_id", nullable = false)
    private Long groupPostCommentId;

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
