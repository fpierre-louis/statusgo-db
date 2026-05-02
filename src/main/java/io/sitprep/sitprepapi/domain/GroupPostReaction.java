package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-user emoji reaction on a {@link GroupPost}. One row per (post, user, emoji) so
 * toggling is exactly add-or-remove and the listing path can return the full
 * roster of who reacted with what.
 *
 * <p>The legacy {@code GroupPost.reactions} {@code Map<String,Integer>} is left in
 * place so Hibernate's {@code ddl-auto} doesn't drop the {@code post_reactions}
 * collection table mid-flight, but it is no longer read or written. The
 * {@link GroupPostReaction} table is the source of truth.</p>
 */
@Entity
@Table(
        name = "post_reaction",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_post_reaction_post_user_emoji",
                columnNames = { "post_id", "user_email", "emoji" }
        ),
        indexes = {
                @Index(name = "idx_post_reaction_post", columnList = "post_id"),
                @Index(name = "idx_post_reaction_user", columnList = "user_email")
        }
)
@Getter
@Setter
public class GroupPostReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

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
