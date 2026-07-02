package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.*;

@Setter
@Getter
@Entity
@Table(
        name = "post",
        indexes = {
                @Index(name = "idx_post_group_ts", columnList = "group_id,timestamp")
        }
)
public class GroupPost {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String author;
    private String content;

    @Column(name = "group_id")
    private String groupId;

    private String groupName;

    /** created-at */
    private Instant timestamp;

    /**
     * R2 object key for the post's image. Format: {@code post/<uuid>.jpg}.
     * Null when the post has no image. Public delivery URL is derived via
     * {@link io.sitprep.sitprepapi.util.PublicCdn#toPublicUrl(String)}.
     */
    @Column(name = "image_key")
    private String imageKey;

    /** user-initiated edit moment */
    private Instant editedAt;

    /** last modified (any change) – used for delta/backfill */
    @Column(name = "updated_at")
    private Instant updatedAt;

    // Pinned explicitly to the existing collection tables. Without this,
    // Hibernate 6's JPA-compliant implicit naming derives the collection
    // table from this entity's PHYSICAL table name ("post" — set by the
    // Phase-3b GroupPost→post rename), i.e. it would expect "post_tags"
    // (join "post_id") instead of the real "group_post_tags" (join
    // "group_post_id"), failing ddl-auto=validate on boot. Explicit
    // mapping → no migration, matches the tables that actually exist.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_post_tags", joinColumns = @JoinColumn(name = "group_post_id"))
    @Column(name = "tags")
    @OrderColumn(name = "ord")
    private List<String> tags = new ArrayList<>();

    private int commentsCount = 0;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_post_mentions", joinColumns = @JoinColumn(name = "group_post_id"))
    @Column(name = "mentions")
    @OrderColumn(name = "ord")
    private List<String> mentions = new ArrayList<>();

    /**
     * Optional shared location (message §D — "Share location" composer row).
     * When both {@code latitude} and {@code longitude} are non-null the FE
     * renders a static-map thumbnail that opens the coordinates on tap.
     * {@code locationLabel} is an optional short place name (reverse-geocoded
     * client-side or left null). Null on every non-location post.
     */
    private Double latitude;
    private Double longitude;

    @Column(name = "location_label")
    private String locationLabel;

    /**
     * When non-null, this post is pinned to the top of its group's feed.
     * Admins of the group toggle it via {@code POST/DELETE /api/group-posts/{id}/pin}.
     * Posts with {@code pinnedAt != null} sort to the top of
     * {@code listGroupPosts} ahead of any timestamp-ordering. Multiple
     * pinned posts in the same group order by {@code pinnedAt DESC} so
     * the most-recently pinned is first.
     *
     * <p>Stays null on every legacy row (default) + on every post that's
     * never been pinned. Unpinning clears both this field and
     * {@link #pinnedBy} so the FE doesn't render a stale "Pinned by X"
     * chip after a pin is removed.</p>
     */
    @Column(name = "pinned_at")
    private Instant pinnedAt;

    /**
     * Email of the admin who last pinned this post. Drives the FE's
     * "📌 Pinned by {firstname}" chip on the post card. Cleared when
     * the post is unpinned.
     */
    @Column(name = "pinned_by", length = 255)
    private String pinnedBy;

    @PrePersist
    public void onCreate() {
        if (timestamp == null) timestamp = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }
}
