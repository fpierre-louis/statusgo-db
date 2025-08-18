package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Setter
@Getter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        indexes = {
                @Index(name = "idx_comment_post_id", columnList = "postId"),
                @Index(name = "idx_comment_updated_at", columnList = "updatedAt")
        }
)
public class Comment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long postId;
    private String author;
    private String content;

    /** Creation time (was already named 'timestamp') */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    /** Last modification time – used for delta/backfill */
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
