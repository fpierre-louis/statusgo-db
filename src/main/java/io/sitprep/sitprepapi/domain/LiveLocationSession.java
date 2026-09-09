package io.sitprep.sitprepapi.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "live_location_sessions")
@Data
public class LiveLocationSession {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "scope_type", nullable = false, length = 32)
    private String scopeType = "group";

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "live_location_session_groups",
            joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "group_id", nullable = false)
    private Set<String> groupIds = new LinkedHashSet<>();

    @Column(name = "activation_id")
    private String activationId;

    @Column(name = "alert_id")
    private String alertId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "upload_token_hash", length = 96)
    private String uploadTokenHash;

    @Column(name = "created_by_user", nullable = false)
    private boolean createdByUser = true;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }
}
