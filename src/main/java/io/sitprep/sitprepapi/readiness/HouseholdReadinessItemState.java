package io.sitprep.sitprepapi.readiness;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One self-reported readiness state for a household step (V98,
 * CONTRACT.md §8).
 *
 * <p>HOUSEHOLD rows ({@code user_email} null) hold DONE / NOT_RELEVANT;
 * USER rows hold SKIPPED / REMIND_LATER for one member. The one-row-per-scope
 * rule is two partial unique indexes in Postgres ({@code uk_hris_household_item},
 * {@code uk_hris_user_item}) that JPA cannot declare and H2 cannot build, so
 * {@link ReadinessJourneyService} enforces it with find-then-update inside one
 * transaction (SYSTEM_TRAPS T-2).</p>
 */
@Entity
@Getter
@Setter
@Table(name = "household_readiness_item_state",
        indexes = @Index(name = "idx_hris_household", columnList = "household_id"))
// idx_hris_remind_due (V99) is partial, so it is not declared here (T-2).
public class HouseholdReadinessItemState {

    public static final String SCOPE_HOUSEHOLD = "HOUSEHOLD";
    public static final String SCOPE_USER = "USER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 255)
    private String householdId;

    @Column(name = "item_key", nullable = false, length = 96)
    private String itemKey;

    /** {@link #SCOPE_HOUSEHOLD} or {@link #SCOPE_USER}. */
    @Column(name = "scope", nullable = false, length = 16)
    private String scope;

    /** Lower-cased; null for HOUSEHOLD rows. */
    @Column(name = "user_email", length = 320)
    private String userEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private ItemStateKind state;

    @Column(name = "suppressed_until")
    private Instant suppressedUntil;

    @Column(name = "remind_at")
    private Instant remindAt;

    /**
     * When this REMIND_LATER row's reminder was handled — sent, or closed
     * because the step no longer needed it (V99). Null until then; a new
     * snooze clears it. Written by {@link ReadinessReminderService} through a
     * conditional update so it is set once.
     */
    @Column(name = "reminded_at")
    private Instant remindedAt;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "created_by", nullable = false, length = 320)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
