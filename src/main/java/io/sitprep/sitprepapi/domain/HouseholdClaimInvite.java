package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Single-use, expiring token that lets one person claim one manual household
 * member as themselves (V100). Accepting it makes the caller a household
 * member, moves every reference from the manual row to their account and
 * deletes the manual row.
 *
 * <p>Not a {@link GroupInvite}: it names a person, is consumed rather than
 * counted, and must never be redeemable through the generic household-invite
 * path. {@code manualMemberId} carries no FK — the manual row is deleted by the
 * claim and this row must outlive it so a re-accept stays idempotent.</p>
 */
@Entity
@Table(name = "household_claim_invite")
@Getter
@Setter
public class HouseholdClaimInvite {

    /** Random, URL-safe; what goes in {@code /claim/{token}}. */
    @Id
    @Column(name = "token", length = 64)
    private String token;

    @Column(name = "household_id", nullable = false, length = 255)
    private String householdId;

    @Column(name = "manual_member_id", nullable = false, length = 64)
    private String manualMemberId;

    @Column(name = "issued_by_email", nullable = false, length = 320)
    private String issuedByEmail;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "consumed_by_email", length = 320)
    private String consumedByEmail;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isLive(Instant now) {
        return consumedAt == null && revokedAt == null && expiresAt != null && now.isBefore(expiresAt);
    }
}
