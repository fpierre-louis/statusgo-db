package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Immutable provider-attempt audit row for one durable agency alert job. */
@Entity
@Table(
        name = "agency_alert_dispatch_attempt",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agency_alert_dispatch_attempt",
                columnNames = {"alert_id", "attempt_number"}),
        indexes = @Index(name = "idx_agency_alert_attempt_alert", columnList = "alert_id,attempt_number")
)
@Getter
@Setter
public class AgencyAlertDispatchAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AgencyAlert.DispatchStatus status;

    @Column(name = "attempted_count", nullable = false)
    private int attemptedCount;

    @Column(name = "delivered_count", nullable = false)
    private int deliveredCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;
}
