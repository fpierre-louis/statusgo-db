package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "live_location_points")
@Data
public class LiveLocationPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "lat", nullable = false)
    private Double lat;

    @Column(name = "lng", nullable = false)
    private Double lng;

    @Column(name = "accuracy_m")
    private Double accuracyM;

    @Column(name = "speed_mps")
    private Double speedMps;

    @Column(name = "heading_deg")
    private Double headingDeg;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (capturedAt == null) {
            capturedAt = createdAt;
        }
    }
}
