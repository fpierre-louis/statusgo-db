package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A user-asked question on the Ask SitPrep surface (StackOverflow pattern,
 * adapted for emergency prep). Anyone reads; only authenticated users can
 * post / vote / accept.
 *
 * <p>{@code zipBucket} (first 3 chars of postcode) is the cheap pre-filter
 * for the local-vs-anywhere toggle — same convention {@link Post} uses
 * for community-discover JPQL. {@code voteScore} is denormalized from the
 * {@link AskVote} table and bumped atomically on vote toggle so the search
 * + ranking endpoints don't need a join per row.</p>
 *
 * <p>{@code acceptedAnswerId} is null until the asker marks one answer as
 * accepted (only the original asker can do this). Pinned answers render
 * first in the FE thread.</p>
 *
 * <p>Hazard tags ({@link #hazardTags}) drive the "Active in your area"
 * pin-to-top behavior: when a {@code Group.activeHazardType} matches one
 * of the question's hazard tags, the FE service-layer pre-sort lifts it
 * into the hazard-matched tier above the rest.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "ask_question",
        indexes = {
                @Index(name = "idx_ask_question_zip", columnList = "zip_bucket"),
                @Index(name = "idx_ask_question_author", columnList = "author_email"),
                @Index(name = "idx_ask_question_score", columnList = "vote_score"),
                @Index(name = "idx_ask_question_created", columnList = "created_at")
        }
)
public class AskQuestion {

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

    /** Free-form lowercased tags, e.g. "water", "evacuation", "kids". */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ask_question_tags", joinColumns = @JoinColumn(name = "question_id"))
    @Column(name = "tag", length = 64)
    private Set<String> tags = new HashSet<>();

    /**
     * Hazard tags from a fixed vocabulary ({@code hurricane}, {@code wildfire},
     * {@code earthquake}, {@code blizzard}, {@code flood}, {@code tornado},
     * {@code heat}). Drives "Active in your area" pin-to-top.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ask_question_hazards", joinColumns = @JoinColumn(name = "question_id"))
    @Column(name = "hazard", length = 32)
    private Set<String> hazardTags = new HashSet<>();

    /** Optional location for local vs anywhere filtering. */
    private Double latitude;
    private Double longitude;

    @Column(name = "zip_bucket", length = 8)
    private String zipBucket;

    @Column(name = "place_label", length = 128)
    private String placeLabel;

    /** Sum of (+1/-1) votes from {@link AskVote}. Denormalized for fast sort. */
    @Column(name = "vote_score", nullable = false)
    private int voteScore = 0;

    @Column(name = "view_count", nullable = false)
    private long viewCount = 0;

    @Column(name = "answer_count", nullable = false)
    private int answerCount = 0;

    /** Set when the asker marks one answer accepted. Only one accepted answer per Q. */
    @Column(name = "accepted_answer_id")
    private Long acceptedAnswerId;

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
