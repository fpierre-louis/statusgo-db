package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * When a household last did one drill, and who said so.
 *
 * <p><b>Why this exists.</b> Until 2026-09-01 the only completion record was
 * {@link Group#getChallengeProgress()}, a {@code Map<weekKey, Boolean>}. That
 * answers "did we do our week?" and cannot answer "when did we last do THIS
 * drill?" — the app did not know which drill was done, only that one was, and
 * the "Drills done · N" count on the dashboard has always counted weeks. Two
 * drills in one week recorded as one.</p>
 *
 * <p><b>The key is a drill id, optionally with a phase.</b> {@code "go-bag"}
 * for a whole drill, {@code "go-bag#papers"} for one part of a split one —
 * a household can pack the documents on a different evening from the water,
 * and each part records its own date. The catalog owns both halves of that
 * string ({@code src/me/challenges/challenges.js}); this side only validates
 * the shape.</p>
 *
 * <p><b>Deliberately identical to {@link AdvancedReadinessCompletion}.</b> Two
 * embeddables with the same two columns looks like something to unify, and it
 * is not: they are keyed differently, written by different endpoints under
 * different permissions (drills are member-writable, advanced readiness is
 * admin-only), and merging them would put one table's authorization rule in
 * reach of the other's callers. Same shape, different fact.</p>
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DrillCompletion {

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    /** The member who reported it. Null only for rows written before this field existed. */
    @Column(name = "completed_by", length = 320)
    private String completedBy;
}
