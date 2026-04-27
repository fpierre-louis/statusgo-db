package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One row per recipient response to an activation. The (activation_id,
 * recipient_email) unique constraint means a recipient tapping a different
 * status overwrites their previous ack rather than piling up new rows — so
 * re-opening the deployed-plan link on another device shows the latest state.
 */
@Entity
@Getter
@Setter
@Table(
        name = "plan_activation_acks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_activation_recipient",
                columnNames = {"activation_id", "recipient_email"}
        ),
        indexes = @Index(name = "idx_ack_activation", columnList = "activation_id")
)
public class PlanActivationAck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activation_id", nullable = false)
    private String activationId;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    @Column(name = "recipient_name")
    private String recipientName;

    /** 'safe' | 'help' | 'pickup' */
    @Column(nullable = false)
    private String status;

    private Double lat;
    private Double lng;

    @Column(name = "acked_at", nullable = false)
    private Instant ackedAt;
}
