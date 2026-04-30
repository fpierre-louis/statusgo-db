package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-geocell alert mode state. Implements the state machine in
 * {@code docs/SPONSORED_AND_ALERT_MODE.md}: a single row per
 * populated zip-bucket carries the cell's current mode + the
 * hysteresis dwell that prevents the mode from flapping when
 * triggers wobble.
 *
 * <p><b>Why per-cell, not global:</b> a wildfire in west Maui doesn't
 * suppress sponsored impressions in Hilo. The mode is bounded to the
 * geographic cell where users actually feel the trigger.</p>
 *
 * <p><b>Why a row per cell, not derived on read:</b> hysteresis
 * requires memory (the previous state + when we entered it). A
 * stateless "compute from triggers" derivation would let the mode
 * flap on every trigger blip; persisting state is the mechanism that
 * makes downgrades sticky.</p>
 *
 * <p><b>Triggers JSON</b> stores the active trigger set as a small
 * array — {@code [{source, confidence, payload, since}, ...]} per the
 * spec. JSON-as-text rather than a relational join because (a) the
 * trigger shape is deliberately heterogeneous across sources, (b) we
 * never query INTO the triggers, only render them on the resource
 * response.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "alert_mode_state",
        indexes = {
                @Index(name = "idx_amode_state", columnList = "state"),
                @Index(name = "idx_amode_dwell", columnList = "hysteresis_expires_at")
        }
)
public class AlertModeState {

    /**
     * Geocell key (zip-bucket prefix for v1, switchable to H3 hexes
     * later). Same key the alert dispatcher and community feed use, so
     * the three systems stay in lockstep.
     */
    @Id
    @Column(name = "zip_bucket", nullable = false, length = 32)
    private String zipBucket;

    /** {@code calm | attention | alert | crisis}. Lowercased, stable strings. */
    @Column(nullable = false, length = 16)
    private String state;

    /**
     * JSON array of currently-active triggers driving the state. Small
     * (typically 1–3 entries). Rendered to the FE as-is; not queried.
     */
    @Column(name = "triggers_json", columnDefinition = "TEXT")
    private String triggersJson;

    /** When the cell most recently entered this state. */
    @Column(name = "entered_at", nullable = false)
    private Instant enteredAt;

    /** When we last saw any trigger evidence. */
    @Column(name = "last_trigger_seen")
    private Instant lastTriggerSeen;

    /**
     * Hysteresis dwell — when set, the cell can't drop to a lower
     * state until {@code now > hysteresisExpiresAt}. Triggers can
     * still RAISE the state at any time; only the return path is
     * sticky. Null when no dwell is active (e.g. cell entered crisis,
     * triggers cleared, dwell timer set to expire 6h later).
     */
    @Column(name = "hysteresis_expires_at")
    private Instant hysteresisExpiresAt;

    /** Bookkeeping. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onWrite() {
        updatedAt = Instant.now();
    }
}
