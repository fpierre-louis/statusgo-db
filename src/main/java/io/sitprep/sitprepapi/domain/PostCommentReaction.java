package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-user emoji reaction on a {@link PostComment} (community-feed
 * post comment). One row per (postComment, user, emoji) so toggling
 * is exactly add-or-remove and the listing path can return the full
 * roster of who reacted with what.
 *
 * <p>Mirrors {@link PostReaction} (the post-level reaction) exactly
 * — same column shape, same uniqueness constraint, same indexes —
 * so the lifecycle code can be a near-copy and a future
 * generic-reaction abstraction can collapse the two without churn.</p>
 *
 * <p>The "Thank" affordance on community feed comment bubbles toggles
 * the heart emoji ({@code "❤"}); the table supports the full emoji
 * vocabulary so a future multi-emoji picker reuses the same shape
 * without schema work.</p>
 */
@Entity
@Table(
        name = "post_comment_reaction",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_post_comment_reaction_comment_user_emoji",
                columnNames = { "post_comment_id", "user_email", "emoji" }
        ),
        indexes = {
                @Index(name = "idx_post_comment_reaction_comment", columnList = "post_comment_id"),
                @Index(name = "idx_post_comment_reaction_user", columnList = "user_email")
        }
)
@Getter
@Setter
public class PostCommentReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_comment_id", nullable = false)
    private Long postCommentId;

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
