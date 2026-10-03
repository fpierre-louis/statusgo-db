package io.sitprep.sitprepapi.gamification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * One token a person holds (V94). One row per (person, token) — written only
 * through {@link UserTokenLedgerRepo#insertIfAbsent}, so the unique index is the
 * idempotency guarantee.
 */
@Entity
@Getter
@Setter
@Table(
        name = "user_token_ledger",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_token_ledger_user_token",
                columnNames = {"user_email", "token_key"}),
        indexes = @Index(name = "idx_user_token_ledger_user_earned",
                columnList = "user_email,earned_at")
)
public class UserTokenLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lower-cased verified email. */
    @Column(name = "user_email", nullable = false, length = 320)
    private String userEmail;

    /** {@link TokenKey#name()}. */
    @Column(name = "token_key", nullable = false, length = 64)
    private String tokenKey;

    @Column(name = "earned_at", nullable = false)
    private Instant earnedAt;

    /** Null until the unlock toast (or the Tokens tab) has shown it. */
    @Column(name = "seen_at")
    private Instant seenAt;

    /** The event that triggered the award, for audit — e.g. {@code ASK_ANSWER_CREATED}. */
    @Column(name = "source_event_type", length = 48)
    private String sourceEventType;

    /** The id of the record behind the award — e.g. the answer id. */
    @Column(name = "source_event_id", length = 128)
    private String sourceEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata;
}
