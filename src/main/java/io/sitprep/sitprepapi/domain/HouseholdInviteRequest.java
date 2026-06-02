package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Member-initiated request to add someone to a household.
 *
 * <p>Flow (docs/HOME_HOUSEHOLD_MERGE.md §6):</p>
 * <ol>
 *   <li>A non-admin member taps "Request" on a search hit in the
 *       InviteSheet.</li>
 *   <li>A {@code PENDING} row is written here and a Lane A push fires
 *       to every household admin (category
 *       {@code PENDING_MEMBER_REQUEST}).</li>
 *   <li>An admin opens the deep-linked InviteApprovalSheet and either
 *       approves (the candidate's email lands on
 *       {@code Group.memberEmails}) or declines.</li>
 *   <li>The row is resolved (status flips, resolverEmail + resolvedAt
 *       populate). It stays in the table for audit; a future sweep can
 *       prune resolved rows past 90d.</li>
 * </ol>
 */
@Entity
@Table(
        name = "household_invite_request",
        indexes = {
                @Index(name = "idx_hir_household_status", columnList = "household_id,status"),
                @Index(name = "idx_hir_candidate_status", columnList = "candidate_email,status")
        }
)
public class HouseholdInviteRequest {

    @Id
    @Column(name = "id", length = 36, updatable = false)
    private String id;

    @Column(name = "household_id", nullable = false, length = 64)
    private String householdId;

    @Column(name = "requester_email", nullable = false, length = 255)
    private String requesterEmail;

    @Column(name = "candidate_email", nullable = false, length = 255)
    private String candidateEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolver_email", length = 255)
    private String resolverEmail;

    public enum Status { PENDING, APPROVED, DECLINED }

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = Status.PENDING;
    }

    // ── getters/setters ────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getHouseholdId() { return householdId; }
    public void setHouseholdId(String householdId) { this.householdId = householdId; }

    public String getRequesterEmail() { return requesterEmail; }
    public void setRequesterEmail(String requesterEmail) { this.requesterEmail = requesterEmail; }

    public String getCandidateEmail() { return candidateEmail; }
    public void setCandidateEmail(String candidateEmail) { this.candidateEmail = candidateEmail; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getResolverEmail() { return resolverEmail; }
    public void setResolverEmail(String resolverEmail) { this.resolverEmail = resolverEmail; }
}
