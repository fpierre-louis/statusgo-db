package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Manual verification intake for businesses, organizations, and civic
 * publishers. v1 keeps the trust decision manual: group admins submit
 * the application; SitPrep reviewers approve through a token-gated
 * admin endpoint that reuses the existing verified-publisher flag.
 */
@Entity
@Getter
@Setter
@Table(
        name = "verification_application",
        indexes = {
                @Index(name = "idx_verification_app_group", columnList = "groupId,updatedAt"),
                @Index(name = "idx_verification_app_status", columnList = "status,updatedAt")
        }
)
public class VerificationApplication {

    public enum Status {
        DRAFT,
        SUBMITTED,
        IN_REVIEW,
        NEEDS_INFO,
        APPROVED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String groupId;

    @Column(nullable = false, length = 160)
    private String applicantEmail;

    @Column(length = 64)
    private String accountType;

    @Column(length = 180)
    private String legalName;

    @Column(length = 240)
    private String publicName;

    @Column(length = 400)
    private String website;

    @Column(length = 180)
    private String officialEmail;

    @Column(length = 400)
    private String addressOrJurisdiction;

    @Column(length = 400)
    private String serviceArea;

    @Column(length = 240)
    private String primaryAdmin;

    @Column(length = 240)
    private String backupContact;

    @Column(length = 500)
    private String postingIntent;

    @Column(length = 1000)
    private String proofLinks;

    @Column(length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.DRAFT;

    @Column(length = 1000)
    private String reviewerNotes;

    @Column(length = 160)
    private String reviewerEmail;

    @Column(length = 40)
    private String verifiedKind;

    @Column(length = 160)
    private String approvedPublisherEmail;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant submittedAt;
    private Instant reviewedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = Status.DRAFT;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
