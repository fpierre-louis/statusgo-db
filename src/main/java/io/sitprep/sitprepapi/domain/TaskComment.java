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
 * A comment on a {@link Task} (community-feed post). Mirrors {@link Comment}
 * exactly modulo the foreign key column (post_id → task_id) so the eventual
 * Post/Task entity merge — telegraphed in {@code TaskDto}'s class doc — can
 * collapse {@code comment} + {@code task_comment} into one table with a
 * mechanical migration.
 *
 * <p>Replies use the same content-prefix convention {@link Comment} already
 * uses ({@code "> Replying to {name}:\n> {snippet}\n\n{content}"}). No
 * {@code parent_comment_id} column — threading is content-side and the FE
 * (forked from {@code PostComments.js}) renders the quote block by parsing
 * the prefix. Zero schema cost for replies.</p>
 */
@Setter
@Getter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "task_comment",
        indexes = {
                @Index(name = "idx_task_comment_task_id", columnList = "task_id"),
                @Index(name = "idx_task_comment_updated_at", columnList = "updated_at")
        }
)
public class TaskComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private String author;

    // IMPORTANT: Do NOT use @Lob for Postgres Strings. Map as TEXT/LONGVARCHAR instead
    // (matches the Comment entity convention).
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
}
