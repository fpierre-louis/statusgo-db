// src/main/java/io/sitprep/sitprepapi/domain/Comment.java
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

@Setter
@Getter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "comment",
        indexes = {
                @Index(name = "idx_comment_post_id", columnList = "post_id"),
                @Index(name = "idx_comment_updated_at", columnList = "updated_at")
        }
)
public class Comment {
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
}