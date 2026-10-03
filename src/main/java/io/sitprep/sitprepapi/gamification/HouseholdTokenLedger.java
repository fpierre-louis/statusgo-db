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
 * One token a household holds (V94). Belongs to the household, not to the
 * member whose action earned it — {@link #earnedByEmail} is attribution only.
 * Seen state is per member, in {@link HouseholdTokenSeen}.
 */
@Entity
@Getter
@Setter
@Table(
        name = "household_token_ledger",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_household_token_ledger_household_token",
                columnNames = {"household_id", "token_key"}),
        indexes = @Index(name = "idx_household_token_ledger_household_earned",
                columnList = "household_id,earned_at")
)
public class HouseholdTokenLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code groups.group_id} of a {@code groupType = Household} group. */
    @Column(name = "household_id", nullable = false, length = 255)
    private String householdId;

    @Column(name = "token_key", nullable = false, length = 64)
    private String tokenKey;

    @Column(name = "earned_at", nullable = false)
    private Instant earnedAt;

    /** The member whose action completed the criteria, or null if unknown. */
    @Column(name = "earned_by_email", length = 320)
    private String earnedByEmail;

    @Column(name = "source_event_type", length = 48)
    private String sourceEventType;

    @Column(name = "source_event_id", length = 128)
    private String sourceEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata;
}
