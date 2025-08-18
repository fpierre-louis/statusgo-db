package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.*;

@Setter
@Getter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "post",
        indexes = {
                @Index(name = "idx_post_group_ts", columnList = "group_id,timestamp"),
                @Index(name = "idx_post_group_updated", columnList = "group_id,updatedAt")
        }
)
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String author;
    private String content;

    @Column(name = "group_id")
    private String groupId;

    private String groupName;

    /** Creation time */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    private byte[] image;

    @Transient
    private String base64Image;

    @ElementCollection
    private Map<String, Integer> reactions = new HashMap<>();

    /** When user explicitly edits content (you already used this) */
    private Instant editedAt;

    @ElementCollection
    private List<String> tags = new ArrayList<>();

    private int commentsCount = 0;

    @ElementCollection
    private List<String> mentions = new ArrayList<>();

    /** Last modification time – any change (content, reactions, counts, etc.) */
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
