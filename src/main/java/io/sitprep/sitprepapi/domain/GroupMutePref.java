package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Per-user, per-group notification mute setting. The user's signal
 * "stop pushing me about this circle for a while".
 *
 * <p>{@link #mutedUntil} carries the deadline:
 * <ul>
 *   <li>{@code null} (or row absent) → not muted.</li>
 *   <li>Future timestamp → muted until that instant. Used both for
 *       fixed windows (1 hour / 1 day / 1 week) AND for the
 *       "until I turn it back on" choice (a far-future sentinel
 *       like year 2999 — the FE never displays it as a deadline,
 *       just as "muted indefinitely").</li>
 *   <li>Past timestamp → effectively unmuted; the enforcement
 *       check treats it the same as null. Rows aren't deleted on
 *       expiry — they get overwritten next time the user touches
 *       the setting. Pre-launch we don't run a sweep.</li>
 * </ul></p>
 *
 * <p>Enforcement lives in {@code NotificationService} —
 * group-scoped {@code deliverPresenceAware} calls bypass FCM when
 * the recipient has an active mute for the group (an inbox log
 * row is still written so the missed message is recoverable when
 * the user unmutes + opens the inbox).</p>
 *
 * <p>Composite-key constraint enforces one row per (user, group);
 * the index on {@code user_email} lets {@code /api/me} batch-load
 * a user's mutes in a single query, just like
 * {@link GroupReadState}.</p>
 */
@Entity
@Table(
        name = "group_mute_pref",
        uniqueConstraints = @UniqueConstraint(name = "uq_gmp_user_group", columnNames = {"user_email", "group_id"}),
        indexes = @Index(name = "idx_gmp_user", columnList = "user_email")
)
@Data
public class GroupMutePref {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    /**
     * Mute deadline. See class javadoc for the semantics; null
     * here is the row-level equivalent of "no mute".
     */
    @Column(name = "muted_until")
    private Instant mutedUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
