package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Per-user, per-group "last read" pointer for the circle-card unread
 * count on the Circles list page. {@code unreadCount} for a group is
 * the number of {@link GroupPost} rows in that group whose
 * {@code timestamp} is greater than this user's {@code lastReadAt}.
 *
 * <p>The row is created or refreshed when the user marks the circle
 * read — typically when they open it ({@code POST /api/groups/{id}/read}).
 * If no row exists for a (user, group) pair, the unread count surfaces
 * as 0 — a deliberate "start clean on rollout" choice so existing users
 * don't see a wall of stale unreads on the day this ships.</p>
 *
 * <p>Composite-key constraint enforces one row per (user, group); the
 * index on user_email lets the {@code /api/me} batch load all of a
 * user's read pointers in one query.</p>
 */
@Entity
@Table(
        name = "group_read_state",
        uniqueConstraints = @UniqueConstraint(name = "uq_grs_user_group", columnNames = {"user_email", "group_id"}),
        indexes = @Index(name = "idx_grs_user", columnList = "user_email")
)
@Data
public class GroupReadState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    @Column(name = "last_read_at", nullable = false)
    private Instant lastReadAt;
}
