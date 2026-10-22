package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
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
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class DrillCompletion {

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    /**
     * Legacy reporter email. Null on rows written since V121 (completers are
     * identified by {@link #completedByUserId}); never shipped to clients.
     */
    @Column(name = "completed_by", length = 320)
    private String completedBy;

    /** Stable {@code UserInfo.id} of the member who reported it; null for legacy rows or an unresolved caller. */
    @Column(name = "completed_by_user_id", length = 255)
    private String completedByUserId;

    public DrillCompletion(Instant completedAt, String completedBy) {
        this(completedAt, completedBy, null);
    }

    public DrillCompletion(Instant completedAt, String completedBy, String completedByUserId) {
        this.completedAt = completedAt;
        this.completedBy = completedBy;
        this.completedByUserId = completedByUserId;
    }
}
