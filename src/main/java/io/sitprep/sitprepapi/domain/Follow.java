package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Follow edge — one row per (follower → followed) pair. Per
 * {@code docs/PROFILE_AND_FOLLOW.md}: follow is one-way; mutual
 * following exists naturally when both directions are present, but
 * isn't elevated as a separate "Friend" tier (no friend badge, same
 * UI). Two rows is mutual; the data model stays flat.
 *
 * <p>Emails are the identity key — same convention as the rest of
 * SitPrep ({@code GroupPost.author}, {@code Task.requesterEmail}, group
 * member emails). Stored lowercase by the service layer to keep the
 * unique constraint case-insensitive without a generated column.</p>
 *
 * <p>Indexes: {@code (followerEmail)} for "people I follow" queries,
 * {@code (followedEmail)} for "who follows me / followers count".
 * The unique constraint pulls double duty as the (followerEmail,
 * followedEmail) lookup index.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "follow",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_follow_follower_followed",
                        columnNames = {"follower_email", "followed_email"}
                )
        },
        indexes = {
                @Index(name = "idx_follow_follower", columnList = "follower_email"),
                @Index(name = "idx_follow_followed", columnList = "followed_email")
        }
)
public class Follow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The user who initiated the follow. Lowercase. */
    @Column(name = "follower_email", nullable = false, length = 255)
    private String followerEmail;

    /** The user being followed. Lowercase. */
    @Column(name = "followed_email", nullable = false, length = 255)
    private String followedEmail;

    /** When the follower tapped Follow. Set on @PrePersist. */
    @Column(name = "since", nullable = false)
    private Instant since;

    @PrePersist
    void onCreate() {
        if (since == null) since = Instant.now();
    }
}
