package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupInvite;
import io.sitprep.sitprepapi.repo.GroupInviteRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-issued invite tokens for group sharing.
 *
 * <p>Replaces the previous pattern of putting raw {@code groupId} in
 * share URLs. With tokens:</p>
 * <ul>
 *   <li>Group identity is hidden in URLs.</li>
 *   <li>Invites can expire (default 7 days).</li>
 *   <li>Admins can revoke leaked links.</li>
 *   <li>Invites can be capped to single-use for one-specific-person flows.</li>
 * </ul>
 *
 * <p>Validation states (see {@link InviteState}):</p>
 * <ul>
 *   <li>{@link InviteState#OK} — valid + redeemable.</li>
 *   <li>{@link InviteState#NOT_FOUND} — id doesn't exist.</li>
 *   <li>{@link InviteState#EXPIRED} — past expiresAt.</li>
 *   <li>{@link InviteState#REVOKED} — admin killed it.</li>
 *   <li>{@link InviteState#EXHAUSTED} — usedCount &gt;= maxUses.</li>
 * </ul>
 *
 * <p>Service layer enforces only invite-state rules. Auth (caller is
 * group admin to mint / revoke) lives at the resource layer per the
 * existing GroupResource pattern.</p>
 */
@Service
public class GroupInviteService {

    /** Default invite lifetime. Admins can override per-mint. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private final GroupInviteRepo inviteRepo;
    private final GroupService groupService;

    public GroupInviteService(GroupInviteRepo inviteRepo,
                              GroupService groupService) {
        this.inviteRepo = inviteRepo;
        this.groupService = groupService;
    }

    public enum InviteState {
        OK, NOT_FOUND, EXPIRED, REVOKED, EXHAUSTED
    }

    /**
     * Result of {@link #validate(String)} — either a usable invite or
     * one of the failure states. Callers branch on {@link #state} and
     * either render an error or proceed with the invite.
     */
    public record ValidationResult(InviteState state, GroupInvite invite) {
        public boolean isOk() { return state == InviteState.OK; }
    }

    /**
     * Mint a new invite. Caller is the issuing admin's email — the
     * resource layer must verify they're an admin of the group before
     * calling here.
     *
     * @param groupId       the group to invite into
     * @param issuedByEmail admin email (audit + revoke auth)
     * @param ttl           expiry duration; null → DEFAULT_TTL
     * @param maxUses       cap on redeems; null → unlimited
     */
    @Transactional
    public GroupInvite mint(String groupId,
                            String issuedByEmail,
                            Duration ttl,
                            Integer maxUses) {
        // Existence check up front so we 404 cleanly instead of FK-erroring later.
        Group group = groupService.getGroupByPublicId(groupId);
        if (group == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }

        Instant now = Instant.now();
        GroupInvite invite = new GroupInvite();
        invite.setId(UUID.randomUUID().toString());
        invite.setGroupId(groupId);
        invite.setIssuedByEmail(issuedByEmail);
        invite.setIssuedAt(now);
        invite.setExpiresAt(now.plus(ttl != null ? ttl : DEFAULT_TTL));
        invite.setMaxUses(maxUses);
        invite.setUsedCount(0);
        return inviteRepo.save(invite);
    }

    /**
     * Validate an invite for use. Returns the result + state — the
     * caller decides how to surface failures (404 / 410 / generic).
     */
    @Transactional(readOnly = true)
    public ValidationResult validate(String inviteId) {
        Optional<GroupInvite> opt = inviteRepo.findById(inviteId);
        if (opt.isEmpty()) {
            return new ValidationResult(InviteState.NOT_FOUND, null);
        }
        GroupInvite invite = opt.get();
        if (invite.getRevokedAt() != null) {
            return new ValidationResult(InviteState.REVOKED, invite);
        }
        if (invite.getExpiresAt() != null && Instant.now().isAfter(invite.getExpiresAt())) {
            return new ValidationResult(InviteState.EXPIRED, invite);
        }
        Integer max = invite.getMaxUses();
        Integer used = invite.getUsedCount() == null ? 0 : invite.getUsedCount();
        if (max != null && used >= max) {
            return new ValidationResult(InviteState.EXHAUSTED, invite);
        }
        return new ValidationResult(InviteState.OK, invite);
    }

    /**
     * Atomic increment of usedCount. Called by the redeem flow on
     * actual join (not on bot scrape, not on preview render).
     *
     * <p>Re-checks state inside the transaction so a near-simultaneous
     * "exhaust the last use" race results in one redeem succeeding,
     * the other returning EXHAUSTED.</p>
     */
    @Transactional
    public ValidationResult markRedeemed(String inviteId) {
        Optional<GroupInvite> opt = inviteRepo.findById(inviteId);
        if (opt.isEmpty()) {
            return new ValidationResult(InviteState.NOT_FOUND, null);
        }
        GroupInvite invite = opt.get();

        if (invite.getRevokedAt() != null) {
            return new ValidationResult(InviteState.REVOKED, invite);
        }
        if (invite.getExpiresAt() != null && Instant.now().isAfter(invite.getExpiresAt())) {
            return new ValidationResult(InviteState.EXPIRED, invite);
        }
        Integer max = invite.getMaxUses();
        int used = invite.getUsedCount() == null ? 0 : invite.getUsedCount();
        if (max != null && used >= max) {
            return new ValidationResult(InviteState.EXHAUSTED, invite);
        }

        invite.setUsedCount(used + 1);
        GroupInvite saved = inviteRepo.save(invite);
        return new ValidationResult(InviteState.OK, saved);
    }

    /**
     * Admin revoke. Caller-auth verified at resource layer.
     */
    @Transactional
    public void revoke(String inviteId) {
        inviteRepo.findById(inviteId).ifPresent(invite -> {
            if (invite.getRevokedAt() == null) {
                invite.setRevokedAt(Instant.now());
                inviteRepo.save(invite);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<GroupInvite> listActive(String groupId) {
        return inviteRepo.findActiveByGroup(groupId, Instant.now());
    }
}
