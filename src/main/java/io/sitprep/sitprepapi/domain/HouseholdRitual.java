package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One opt-in recurring household ritual — currently just "weekly
 * check-in", but the schema is shaped for additive kinds (monthly drill,
 * quarterly plan review, etc.) without a migration.
 *
 * <p>§4 of {@code docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md}: rituals are
 * the actual retention mechanic for emergency prep. The doc's ethical
 * line is strict — must be opt-in, never assigned. An emergency app that
 * pushes weekly "are you safe?" prompts without consent trains users to
 * ignore real alerts; opt-in flips that incentive (a ritual the user
 * chose is one they'll keep).</p>
 *
 * <p>Householdid is a group id under the hood — household IS a group
 * with {@code groupType = "Household"}. Mirrors the column convention
 * used by {@link HouseholdEvent}.</p>
 *
 * <p><b>Scope of Round 1 (this entity ships):</b> CRUD only. The
 * scheduled fire + push dispatch ships in a focused Round 2 commit so
 * the timing logic + notification side-effects can be tested in
 * isolation. Until Round 2 lands, opted-in rituals exist in storage
 * but no notifications fire — the FE row reflects the persisted
 * opt-in state correctly, just without the recurring nudge yet.</p>
 */
@Entity
@Table(
        name = "household_ritual",
        indexes = {
                @Index(name = "idx_household_ritual_hh", columnList = "household_id"),
                @Index(name = "idx_household_ritual_kind", columnList = "kind")
        }
)
@Getter
@Setter
public class HouseholdRitual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 64)
    private String householdId;

    /**
     * Kind discriminator. Round 1 supports only {@code "check-in"};
     * future kinds (drill, plan-review) layer in by extending the
     * service-layer switch on this field.
     */
    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    /**
     * Schedule spec, opaque string. Round 1 ships only the constant
     * {@code "WEEKLY_SUN_19:00"} (Sunday 7pm local time). v2's picker UI
     * lets the user pick day-of-week + hour; the service will then parse
     * the spec into a fire-time predicate.
     */
    @Column(name = "schedule_spec", nullable = false, length = 64)
    private String scheduleSpec;

    /**
     * IANA timezone string ({@code "America/Denver"} etc.) the schedule
     * is evaluated in. Falls back to the household's saved address-derived
     * timezone or to {@code "America/Denver"} when neither resolves —
     * the FE creates rituals with the current viewer's profile timezone
     * when known.
     */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    /** Email of the household admin who opted in. Audit trail. */
    @Column(name = "opted_in_by_email", nullable = false, length = 255)
    private String optedInByEmail;

    /**
     * When the Round 2 scheduler last fired this ritual. Null until the
     * first fire; the scheduler uses this + the schedule spec to compute
     * "is this due now?" Round 1 leaves this null since no scheduler runs.
     */
    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    /**
     * §4 R5 — pause-until timestamp. NULL = active. Non-NULL future =
     * paused until this instant; the scheduler's {@code isDueNow}
     * returns false while {@code now < pausedUntil}. Lets a household
     * skip a week (or longer) without deleting and re-creating the
     * ritual + losing the configured day/hour/timezone.
     *
     * <p>Past instants are treated as expired (active) by the
     * scheduler check — the column doesn't need a cleanup sweep since
     * the comparison is forward-looking.</p>
     */
    @Column(name = "paused_until")
    private Instant pausedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
