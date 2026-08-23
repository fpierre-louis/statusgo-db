// src/main/java/io/sitprep/sitprepapi/domain/GroupPostComment.java
package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "comment",
        indexes = {
                @Index(name = "idx_comment_post_id", columnList = "post_id"),
                @Index(name = "idx_comment_updated_at", columnList = "updated_at"),
                @Index(name = "idx_comment_parent_comment_id", columnList = "parent_comment_id")
        }
)
public class GroupPostComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(nullable = false)
    private String author;

    // IMPORTANT: Do NOT use @Lob for Postgres Strings. Map as TEXT/LONGVARCHAR instead.
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** Creation time */
    @CreatedDate
    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    /** Last modification time – used for delta/backfill */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** User-initiated edit moment (explicit) */
    @Column(name = "edited_at")
    private Instant editedAt;

    /**
     * The comment this one replies to. Null on a top-level comment and on every
     * pre-V59 row. Mirrors {@link PostComment#getParentCommentId()} exactly —
     * the two comment families are kept column-for-column identical so the
     * eventual GroupPost/Post merge stays a mechanical migration.
     *
     * <p>Parent delete sets this null (orphan, not cascade). Depth is capped at
     * one in {@code GroupPostCommentService}. Full reasoning in V59.</p>
     */
    @Column(name = "parent_comment_id")
    private Long parentCommentId;

    /**
     * Ids of accounts mentioned in {@link #content}, denormalized from the
     * {@code @[uid:...]} tokens the content carries.
     *
     * <p><b>Content is the source of truth; this is the index.</b> The write
     * path re-derives this list from the content on every create and update, so
     * the two cannot disagree -- there is no path that sets one without the
     * other. It exists because the notify path needs "who was mentioned"
     * without parsing prose, and because "threads where I was mentioned" should
     * be an indexed lookup rather than a scan.</p>
     *
     * <p>EAGER to match the sibling collections on these entities, and because
     * a thread render reads every comment's mentions at once -- lazy would turn
     * one page of comments into one query per comment.</p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "comment_mentions", joinColumns = @JoinColumn(name = "comment_id"))
    @Column(name = "mentioned_user_id")
    @OrderColumn(name = "ord")
    private List<String> mentionedUserIds = new ArrayList<>();
}
