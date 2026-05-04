package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * An answer to an {@link AskQuestion}. Many answers per question, only one
 * can be marked accepted (asker-only action stored on {@link AskQuestion#acceptedAnswerId}).
 *
 * <p>{@code voteScore} is denormalized from {@link AskVote} (target='answer')
 * the same way questions denormalize, so the FE answer list can sort by
 * accepted-first-then-score without a join.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "ask_answer",
        indexes = {
                @Index(name = "idx_ask_answer_question", columnList = "question_id"),
                @Index(name = "idx_ask_answer_author", columnList = "author_email"),
                @Index(name = "idx_ask_answer_score", columnList = "vote_score")
        }
)
public class AskAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "author_email", nullable = false, length = 320)
    private String authorEmail;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "vote_score", nullable = false)
    private int voteScore = 0;

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
