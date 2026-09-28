package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A viewer following a community thread ({@link Post}) — "notify me about
 * replies". The thread header's Follow bell (Community Thread C, B2 / V86).
 *
 * <p>Not {@link Follow}: that is person→person (follow an author). This is
 * person→thread. It is READ by {@code PostCommentService}, which pushes a
 * reply notification to every follower — a follow that delivered nothing
 * would be a fake feature.</p>
 *
 * <p>Shape mirrors {@link PostConfirm}: one row per (post, person).</p>
 */
@Entity
@Table(
        name = "post_follow",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_post_follow_task_user",
                columnNames = { "task_id", "user_email" }
        ),
        indexes = {
                @Index(name = "idx_post_follow_task", columnList = "task_id"),
                @Index(name = "idx_post_follow_user", columnList = "user_email")
        }
)
@Getter
@Setter
public class PostFollow {

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
