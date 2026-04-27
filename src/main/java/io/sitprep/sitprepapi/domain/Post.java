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
public class Post {
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
     * Legacy: inline JPEG bytes. Kept temporarily so existing rows still
     * render. New posts write {@link #imageKey} instead. Drop this column
     * once any pre-R2 rows have been migrated or evicted.
     */
    private byte[] image;

    /**
     * R2 object key for posts uploaded via the {@code /api/images} pipe.
     * Format: {@code post/<uuid>.jpg}. Null when the post has no image
     * or predates the R2 migration.
     */
    @Column(name = "image_key")
    private String imageKey;

    @Transient
    private String base64Image;

    @ElementCollection(fetch = FetchType.EAGER)
    private Map<String, Integer> reactions = new HashMap<>();

    /** user-initiated edit moment */
    private Instant editedAt;

    /** last modified (any change) – used for delta/backfill */
    @Column(name = "updated_at")
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> tags = new ArrayList<>();

    private int commentsCount = 0;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> mentions = new ArrayList<>();

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
