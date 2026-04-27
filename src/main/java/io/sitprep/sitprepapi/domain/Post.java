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
     * R2 object key for the post's image. Format: {@code post/<uuid>.jpg}.
     * Null when the post has no image. Public delivery URL is derived via
     * {@link io.sitprep.sitprepapi.util.PublicCdn#toPublicUrl(String)}.
     *
     * <p>The legacy {@code byte[] image} column has been removed from the
     * entity; the DB column itself is left in place (unused) for hibernate
     * {@code ddl-auto: update} compatibility. Drop with a manual ALTER
     * once the schema is ready.</p>
     */
    @Column(name = "image_key")
    private String imageKey;

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
