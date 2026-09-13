package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * A condition the household says is affecting it right now.
 *
 * <p>── WHY THIS IS ITS OWN THING ───────────────────────────────────────────
 *
 * <p>The stress-test matrix kept producing the same shape and finding nowhere
 * to put it: <i>"do not drink the tap water until cleared"</i>,
 * <i>"power expected off 2-6 PM"</i>, <i>"primary vehicle unavailable"</i>,
 * <i>"the access road is still closed"</i>. Every existing primitive was tried
 * first and each is wrong in a specific way:
 *
 * <ul>
 *   <li>{@link PlanActivation} / active situation — bound to an official alert,
 *       expires in 72 hours, and carries a movement directive. A standing
 *       condition must OUTLIVE alerts and instructs nothing about movement.</li>
 *   <li>A post — chronological, scrolls away, has no lifecycle. The
 *       grandmother who keeps using the tap will not scroll back three days.</li>
 *   <li>A task — carries assignee and completion semantics. A condition is not
 *       work somebody finishes; conflating them would drag in exactly the
 *       assignment-vs-acknowledgement boundary RC-3 was careful to draw.</li>
 *   <li>A household plan field — permanent preparedness data. These are
 *       temporary by definition.</li>
 *   <li>An {@link EmergencySupportProfile} — person-scoped and durable. "Power
 *       off 2-6 PM" is about the household; "needs powered equipment" is about
 *       a person. Keeping them apart is what stops this becoming care
 *       management.</li>
 * </ul>
 *
 * <p>── THE INVARIANT ──────────────────────────────────────────────────────
 *
 * <p><b>Only a person clears a condition.</b> Nothing in the alert pipeline may
 * touch {@code status}. The boil-water notice disappears from the CAP feed the
 * moment the county stops republishing it; the household still cannot drink the
 * water. Auto-clearing on alert expiry would delete the fact precisely when it
 * is least likely to be re-noticed.
 *
 * <p>── WHAT IT IS NOT ─────────────────────────────────────────────────────
 *
 * <p>It is household-AUTHORED and must never be presented as an official
 * instruction. Official protective actions outrank it everywhere: "Evacuate
 * now" with "Household update: primary vehicle unavailable" underneath, never
 * the reverse.
 */
@Entity
@Table(name = "household_standing_condition")
@Data
public class HouseholdStandingCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 64)
    private String householdId;

    /**
     * WATER | POWER | TRANSPORTATION | ACCESS | COMMUNICATION | SUPPLIES | OTHER.
     *
     * <p>Drives grouping and iconography and nothing else. No safety guidance is
     * ever derived from it — the household's {@code instruction} is the
     * instruction, and inferring one from a category would be the app inventing
     * advice nobody gave.
     */
    @Column(nullable = false, length = 32)
    private String category;

    /** Short: "Do not drink the tap water". */
    @Column(nullable = false, length = 120)
    private String title;

    /** What everyone should know or do. Optional; may be blank. */
    @Column(length = 500)
    private String instruction;

    /** ACTIVE | CLEARED. */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_email", length = 255)
    private String createdByEmail;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_email", length = 255)
    private String updatedByEmail;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "cleared_by_email", length = 255)
    private String clearedByEmail;

    public boolean isActive() {
        return !"CLEARED".equalsIgnoreCase(status);
    }
}
