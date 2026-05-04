package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A user-authored prep tip / short article — the "blogs from neighbors"
 * surface alongside Questions. Long-form enough to stand on its own
 * (longer than a feed Post, shorter than a Guide). Voted but not answered:
 * the discussion mode is "useful / not useful," not "what's the answer."
 *
 * <p>Same {@code zipBucket} pre-filter convention {@link AskQuestion} uses
 * for local-vs-anywhere. Same {@code hazardTags} vocabulary so hazard pinning
 * works uniformly across the Ask surface.</p>
 *
 * <p>{@code coverImageKey} is optional — the FE composer accepts a single
 * cover image (R2 upload via the existing {@code /api/images} pipeline).</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "ask_tip",
        indexes = {
                @Index(name = "idx_ask_tip_zip", columnList = "zip_bucket"),
                @Index(name = "idx_ask_tip_author", columnList = "author_email"),
                @Index(name = "idx_ask_tip_score", columnList = "vote_score"),
                @Index(name = "idx_ask_tip_created", columnList = "created_at")
        }
)
public class AskTip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author_email", nullable = false, length = 320)
    private String authorEmail;

    @Column(nullable = false, length = 200)
    private String title;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "cover_image_key", length = 256)
    private String coverImageKey;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ask_tip_tags", joinColumns = @JoinColumn(name = "tip_id"))
    @Column(name = "tag", length = 64)
    private Set<String> tags = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ask_tip_hazards", joinColumns = @JoinColumn(name = "tip_id"))
    @Column(name = "hazard", length = 32)
    private Set<String> hazardTags = new HashSet<>();

    /** Optional inline image keys beyond the cover, in display order. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ask_tip_image_keys", joinColumns = @JoinColumn(name = "tip_id"))
    @Column(name = "image_key", length = 256)
    private List<String> imageKeys = new ArrayList<>();

    private Double latitude;
    private Double longitude;

    @Column(name = "zip_bucket", length = 8)
    private String zipBucket;

    @Column(name = "place_label", length = 128)
    private String placeLabel;

    @Column(name = "vote_score", nullable = false)
    private int voteScore = 0;

    @Column(name = "view_count", nullable = false)
    private long viewCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
