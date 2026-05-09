package io.sitprep.sitprepapi.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Server-issued invite token for joining a group.
 *
 * <p>Replaces the previous pattern of putting the raw {@code groupId}
 * directly in share URLs (e.g. {@code /share/group/{groupId}}). With
 * a token, the share URL ({@code /share/i/{inviteId}}) doesn't expose
 * the group's identity, links can expire, admins can revoke leaked
 * links, and individual invites can be capped to single-use for the
 * "share with one specific person" flow.</p>
 *
 * <p>The {@code id} is the public token that goes in URLs — we use
 * a UUID rather than encoding the groupId so the two namespaces stay
 * unrelated (an attacker with one token can't derive others).</p>
 *
 * <p>Backward compat: the legacy {@code /share/group/{groupId}}
 * endpoint stays live so existing share-link recipients can still
 * convert. New shares from {@code GroupShareSheet} mint and use the
 * tokenized URL.</p>
 */
@Entity
@Table(name = "group_invites")
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupInvite {

    /** Token id — what appears in {@code /share/i/{this}}. UUID. */
    @Id
    @Column(name = "invite_id", unique = true, nullable = false)
    private String id;

    /** FK to the target group. */
    @Column(name = "group_id", nullable = false)
    private String groupId;

    /** Admin email that minted the invite — for audit + revocation auth. */
    @Column(name = "issued_by_email", nullable = false)
    private String issuedByEmail;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    /**
     * Hard expiry. Past this instant, the invite returns 410 Gone.
     * Default 7 days from issuance — adjustable per-invite for
     * admins who want longer-lived public-facing invites.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Optional cap on uses. {@code null} means unlimited (the default
     * for shareable links). {@code 1} means "single-use" — the
     * canonical pattern for sending an invite to one specific person.
     * Counter is incremented on successful redeem (not on bot OG-
     * scrape, not on preview render).
     */
    @Column(name = "max_uses")
    private Integer maxUses;

    /** Number of redeems so far. */
    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    /** Set when an admin revokes; non-null = invite is dead. */
    @Column(name = "revoked_at")
    private Instant revokedAt;
}
