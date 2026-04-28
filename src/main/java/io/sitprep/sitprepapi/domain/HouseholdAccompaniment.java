package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Active "with me" claim — one supervisor accompanying one other person
 * inside a household. A person can be accompanied by at most one
 * supervisor at a time (claiming someone who's already with X moves them);
 * a supervisor can supervise multiple.
 *
 * <p>References use a (kind, id) tuple so the same row can point at either
 * an app user (kind=user, id=lower-cased email) or a manual member
 * (kind=manual, id=ManualMember.id). The frontend's ref shape mirrors
 * this exactly.</p>
 *
 * <p>{@code pending} is true when the accompanied is an app user who hasn't
 * confirmed yet. Manual members auto-confirm on insert.</p>
 */
@Entity
@Table(
        name = "household_accompaniment",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_household_accompaniment_target",
                columnNames = { "household_id", "accompanied_kind", "accompanied_id" }
        ),
        indexes = {
                @Index(name = "idx_household_accompaniment_household", columnList = "household_id"),
                @Index(name = "idx_household_accompaniment_supervisor",
                       columnList = "household_id,supervisor_kind,supervisor_id")
        }
)
@Getter
@Setter
public class HouseholdAccompaniment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 64)
    private String householdId;

    @Column(name = "supervisor_kind", nullable = false, length = 16)
    private String supervisorKind;       // "user" | "manual"

    @Column(name = "supervisor_id", nullable = false, length = 255)
    private String supervisorId;

    @Column(name = "accompanied_kind", nullable = false, length = 16)
    private String accompaniedKind;

    @Column(name = "accompanied_id", nullable = false, length = 255)
    private String accompaniedId;

    @Column(nullable = false)
    private Instant since;

    /**
     * True when the accompanied is an app user and hasn't confirmed yet.
     * Manual members are always non-pending. The crisis-override path
     * (admin claiming a minor during an active alert) inserts non-pending
     * but is logged via HouseholdEvent.
     */
    @Column(nullable = false)
    private boolean pending;

    @PrePersist
    void onCreate() {
        if (since == null) since = Instant.now();
    }
}
