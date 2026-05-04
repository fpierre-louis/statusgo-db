package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-user bookmark on an Ask-surface entity (guide / question / tip).
 * Powers the "Saved" tab on /ask. Toggle endpoint inserts or deletes;
 * the row's mere existence = bookmarked.
 *
 * <p>Polymorphic the same way {@link AskVote} is — {@code targetType} +
 * {@code targetId}. {@code "guide"} targets reference the slug-keyed
 * hardcoded SitPrep Guides for v1; when the Guide entity ships, it
 * becomes a numeric id like the others.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "ask_bookmark",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ask_bookmark_user_target",
                columnNames = { "user_email", "target_type", "target_key" }
        ),
        indexes = {
                @Index(name = "idx_ask_bookmark_user", columnList = "user_email,created_at"),
                @Index(name = "idx_ask_bookmark_target", columnList = "target_type,target_key")
        }
)
public class AskBookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", nullable = false, length = 320)
    private String userEmail;

    /** "guide" | "question" | "tip". */
    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    /**
     * Stringified target identifier — numeric for question/tip, slug for
     * guide. String typing keeps the same column shape across both worlds
     * so we don't need separate tables.
     */
    @Column(name = "target_key", nullable = false, length = 128)
    private String targetKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
