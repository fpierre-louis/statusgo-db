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

    @ElementCollection(fetch = FetchType.EAGER)
    @OrderColumn(name = "ord")
    private List<String> tags = new ArrayList<>();

    private int commentsCount = 0;

    @ElementCollection(fetch = FetchType.EAGER)
    @OrderColumn(name = "ord")
    private List<String> mentions = new ArrayList<>();

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
