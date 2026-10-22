package io.sitprep.sitprepapi.practice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * An in-progress Together session (V123): where the household is, so a closed app can resume.
 * Deliberately nothing else: no child, no age, no role holder, no actor. Gone at 24 h, on
 * completion, and with "Clear practice history".
 */
@Entity
@Getter
@Setter
@Table(name = "practice_together_session")
public class PracticeTogetherSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, length = 255)
    private String householdId;

    @Column(name = "activity_key", nullable = false, length = 96)
    private String activityKey;

    @Column(name = "content_version", nullable = false)
    private int contentVersion;

    /** A {@link TogetherStyle} name. */
    @Column(name = "style", nullable = false, length = 24)
    private String style;

    @Column(name = "turn_index", nullable = false)
    private int turnIndex;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
