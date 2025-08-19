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

    private byte[] image;

    @Transient
    private String base64Image;

    @ElementCollection
    private Map<String, Integer> reactions = new HashMap<>();

    /** user-initiated edit moment */
    private Instant editedAt;

    /** last modified (any change) – used for delta/backfill */
    @Column(name = "updated_at")
    private Instant updatedAt;

    @ElementCollection
    private List<String> tags = new ArrayList<>();

    private int commentsCount = 0;

    @ElementCollection
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
