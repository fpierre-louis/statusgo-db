package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter
@Setter
@Table(
        name = "legal_agreements",
        indexes = {
                @Index(name = "idx_legal_agreements_user", columnList = "user_id,accepted_at"),
                @Index(name = "idx_legal_agreements_firebase", columnList = "firebase_uid,accepted_at"),
                @Index(name = "idx_legal_agreements_doc_version", columnList = "document_type,policy_version")
        }
)
public class LegalAgreement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "firebase_uid", nullable = false, length = 128)
    private String firebaseUid;

    @Column(name = "user_email", length = 320)
    private String userEmail;

    @Column(name = "document_type", nullable = false, length = 48)
    private String documentType;

    @Column(name = "policy_version", nullable = false, length = 120)
    private String policyVersion;

    @Column(name = "effective_date", nullable = false, length = 32)
    private String effectiveDate;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "authentication_state", nullable = false, length = 24)
    private String authenticationState;

    @Column(name = "acceptance_surface", nullable = false, length = 120)
    private String acceptanceSurface;

    @PrePersist
    void onCreate() {
        if (acceptedAt == null) acceptedAt = Instant.now();
    }
}
