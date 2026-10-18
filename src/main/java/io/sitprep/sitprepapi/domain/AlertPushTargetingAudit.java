package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One severe NWS push under the R19 containment rule (V111): counts only, never
 * who. See {@code PushTargeting}.
 */
@Entity
@Table(name = "alert_push_targeting_audit",
        indexes = @Index(name = "idx_aupta_created", columnList = "created_at"))
@Getter
@Setter
public class AlertPushTargetingAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "alert_ref", nullable = false, length = 320)
    private String alertRef;

    /** {@code outline+5km}, {@code zones} or {@code proximity} (no area to test). */
    @Column(name = "basis", nullable = false, length = 32)
    private String basis;

    @Column(name = "reached_here", nullable = false)
    private int reachedHere;

    @Column(name = "reached_home", nullable = false)
    private int reachedHome;

    @Column(name = "fallback_proximity", nullable = false)
    private int fallbackProximity;

    @Column(name = "proximity_would", nullable = false)
    private int proximityWould;

    @Column(name = "added_vs_proximity", nullable = false)
    private int addedVsProximity;

    @Column(name = "dropped_vs_proximity", nullable = false)
    private int droppedVsProximity;
}
