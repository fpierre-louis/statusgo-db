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

/**
 * A comment on a {@link Post} (community-feed post). Mirrors {@link GroupPostComment}
 * exactly modulo the foreign key column (post_id → task_id) so the eventual
 * GroupPost/Post entity merge — telegraphed in {@code PostDto}'s class doc — can
 * collapse {@code comment} + {@code task_comment} into one table with a
 * mechanical migration.
 *
 * <p><b>Replies carry a real parent reference</b> ({@link #parentCommentId},
 * V59). The prior convention was a content prefix
 * ({@code "> Replying to {name}:\n> {snippet}\n\n{content}"}) parsed by the
 * FE — zero schema cost, and it could not nest, could not be permalinked,
 * could not be collapsed, and duplicated text that went stale when the parent
 * was edited. It also COMPOUNDED: each reply re-quoted the whole quoted chain
 * above it, so the prefix grew with depth while the message stayed one word.</p>
 *
 * <p>Pre-V59 comments keep their prefix and no parent id. The FE prefix parser
 * still handles them; see the migration for why 8 rows were not backfilled.</p>
 */
@Setter
@Getter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "task_comment",
        indexes = {
                @Index(name = "idx_task_comment_task_id", columnList = "task_id"),
                @Index(name = "idx_task_comment_updated_at", columnList = "updated_at"),
                @Index(name = "idx_task_comment_parent_comment_id", columnList = "parent_comment_id")
        }
)
public class PostComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long postId;

    @Column(nullable = false)
    private String author;

    // IMPORTANT: Do NOT use @Lob for Postgres Strings. Map as TEXT/LONGVARCHAR instead
    // (matches the GroupPostComment entity convention).
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** Creation time (auditing-managed). */
    @CreatedDate
    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    /** Last modification time — used for delta/backfill (auditing-managed). */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** User-initiated edit moment (explicit). Null on never-edited comments. */
    @Column(name = "edited_at")
    private Instant editedAt;

    /**
     * The comment this one replies to. Null on a top-level comment and on every
     * pre-V59 row.
     *
     * <p>Plain scalar rather than {@code @ManyToOne}, matching how
     * {@code Post.parentPostId} models the repost pointer: the reply needs the
     * id, never the object graph, and a lazy association here would issue a
     * query per row on a thread render.</p>
     *
     * <p><b>DELETE OF THE PARENT SETS THIS NULL</b> (FK {@code ON DELETE SET
     * NULL}), promoting the reply to top level. Not cascade — a comment's
     * replies are not its author's property, and a moderator removing one
     * comment must not silently delete a stranger's answer under it.</p>
     *
     * <p><b>DEPTH IS CAPPED AT ONE</b> in the service, not the schema: a reply
     * to a reply is re-pointed at the root. See
     * {@code PostCommentService.resolveParent}.</p>
     */
    @Column(name = "parent_comment_id")
    private Long parentCommentId;
}
