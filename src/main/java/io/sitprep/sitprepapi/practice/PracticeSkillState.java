package io.sitprep.sitprepapi.practice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A household's spaced-recall state for one skill (V122). Written only by a check-in
 * answer (or its confidence tap); never by a drill or scenario, whose exposure is
 * read where it already lives.
 */
@Entity
@Getter
@Setter
@Table(name = "practice_skill_state", uniqueConstraints = @UniqueConstraint(
        name = "uk_practice_skill_state_household_skill", columnNames = {"household_id", "skill_key"}))
public class PracticeSkillState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 255)
    private String householdId;

    @Column(name = "skill_key", nullable = false, length = 96)
    private String skillKey;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** The interval the newest answer chose. */
    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    /** The interval the newest answer was scheduled from (what a confidence tap re-schedules against). */
    @Column(name = "prior_interval_days", nullable = false)
    private int priorIntervalDays;

    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;

    /** {@code CORRECT} or {@code INCORRECT}; internal. */
    @Column(name = "last_result", nullable = false, length = 16)
    private String lastResult;

    @Column(name = "last_confidence", length = 16)
    private String lastConfidence;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
