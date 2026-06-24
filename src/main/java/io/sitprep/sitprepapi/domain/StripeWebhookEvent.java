package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter
@Setter
@Table(
        name = "stripe_webhook_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_stripe_webhook_event_id",
                columnNames = "stripe_event_id")
)
public class StripeWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stripe_event_id", nullable = false, length = 255)
    private String stripeEventId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "live_mode", nullable = false)
    private boolean liveMode;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "group_id", length = 64)
    private String groupId;

    @Column(length = 1000)
    private String detail;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) receivedAt = Instant.now();
    }
}
