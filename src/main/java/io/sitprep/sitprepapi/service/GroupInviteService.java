package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupInvite;
import io.sitprep.sitprepapi.domain.GroupInviteRedemption;
import io.sitprep.sitprepapi.repo.GroupInviteRepo;
import io.sitprep.sitprepapi.repo.GroupInviteRedemptionRepo;
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
    private final GroupInviteRedemptionRepo redemptionRepo;
    private final GroupService groupService;

    public GroupInviteService(GroupInviteRepo inviteRepo,
                              GroupInviteRedemptionRepo redemptionRepo,
                              GroupService groupService) {
        this.inviteRepo = inviteRepo;
        this.redemptionRepo = redemptionRepo;
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

    public record RedemptionResult(InviteState state, GroupInvite invite, Group group, boolean alreadyRedeemed) {
        public boolean isOk() { return state == InviteState.OK; }
    }

    public record HouseholdInvitePreview(InviteState state, GroupInvite invite, Group household) {
        public boolean isOk() { return state == InviteState.OK && household != null; }
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
     * Redeem a token into its target group. The redemption row makes the flow
     * idempotent per user, so a double tap or post-login retry does not consume
     * a capped invite twice.
     */
    @Transactional
    public RedemptionResult redeem(String inviteId, String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Caller email required");
        }
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);

        var existingRedemption = redemptionRepo.findByInviteIdAndUserEmail(inviteId, normalizedEmail);
        if (existingRedemption.isPresent()) {
            // A repeat tap is idempotent, but only while the token still
            // stands (JN-17). Without this, someone removed from the group
            // could re-join from a link an admin had already revoked.
            InviteState again = repeatStateFor(inviteId);
            if (again != InviteState.OK) {
                return new RedemptionResult(again, null, null, true);
            }
            String groupId = existingRedemption.get().getGroupId();
            if (isHouseholdId(groupId)) {
                return new RedemptionResult(InviteState.NOT_FOUND, null, null, true);
            }
            Group group = groupService.selfJoin(groupId, normalizedEmail);
            return new RedemptionResult(InviteState.OK, null, group, true);
        }

        Optional<GroupInvite> opt = inviteRepo.findByIdForUpdate(inviteId);
        if (opt.isEmpty()) {
            return new RedemptionResult(InviteState.NOT_FOUND, null, null, false);
        }

        GroupInvite invite = opt.get();
        InviteState state = stateFor(invite, Instant.now());
        if (state != InviteState.OK) {
            return new RedemptionResult(state, invite, null, false);
        }
        // A household token is not a group invite (JN-17): it redeems through
        // /household-invites, where the token is the admin's approval.
        if (isHouseholdId(invite.getGroupId())) {
            return new RedemptionResult(InviteState.NOT_FOUND, null, null, false);
        }

        Group group = groupService.selfJoin(invite.getGroupId(), normalizedEmail);

        GroupInviteRedemption redemption = new GroupInviteRedemption();
        redemption.setInviteId(inviteId);
        redemption.setUserEmail(normalizedEmail);
        redemption.setGroupId(invite.getGroupId());
        redemption.setRedeemedAt(Instant.now());
        redemptionRepo.save(redemption);

        int used = invite.getUsedCount() == null ? 0 : invite.getUsedCount();
        invite.setUsedCount(used + 1);
        GroupInvite saved = inviteRepo.save(invite);
        return new RedemptionResult(InviteState.OK, saved, group, false);
    }

    @Transactional(readOnly = true)
    public HouseholdInvitePreview previewHousehold(String inviteId) {
        ValidationResult validation = validate(inviteId);
        if (!validation.isOk()) {
            return new HouseholdInvitePreview(validation.state(), validation.invite(), null);
        }
        Group household;
        try {
            household = groupService.getGroupByPublicId(validation.invite().getGroupId());
        } catch (RuntimeException e) {
            return new HouseholdInvitePreview(InviteState.NOT_FOUND, validation.invite(), null);
        }
        if (!isHousehold(household)) {
            return new HouseholdInvitePreview(InviteState.NOT_FOUND, validation.invite(), null);
        }
        return new HouseholdInvitePreview(InviteState.OK, validation.invite(), household);
    }

    @Transactional
    public RedemptionResult redeemHousehold(String inviteId, String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Caller email required");
        }
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);

        var existingRedemption = redemptionRepo.findByInviteIdAndUserEmail(inviteId, normalizedEmail);
        if (existingRedemption.isPresent()) {
            // Same rule as the group path (JN-17): a revoked or expired link
            // does not put a removed family member back.
            InviteState again = repeatStateFor(inviteId);
            if (again != InviteState.OK) {
                return new RedemptionResult(again, null, null, true);
            }
            Group group = groupService.joinHouseholdByInvite(
                    existingRedemption.get().getGroupId(), normalizedEmail);
            return new RedemptionResult(InviteState.OK, null, group, true);
        }

        Optional<GroupInvite> opt = inviteRepo.findByIdForUpdate(inviteId);
        if (opt.isEmpty()) {
            return new RedemptionResult(InviteState.NOT_FOUND, null, null, false);
        }

        GroupInvite invite = opt.get();
        InviteState state = stateFor(invite, Instant.now());
        if (state != InviteState.OK) {
            return new RedemptionResult(state, invite, null, false);
        }

        Group group;
        try {
            group = groupService.getGroupByPublicId(invite.getGroupId());
        } catch (RuntimeException e) {
            return new RedemptionResult(InviteState.NOT_FOUND, invite, null, false);
        }
        if (!isHousehold(group)) {
            return new RedemptionResult(InviteState.NOT_FOUND, invite, null, false);
        }

        Group joined = groupService.joinHouseholdByInvite(invite.getGroupId(), normalizedEmail);

        GroupInviteRedemption redemption = new GroupInviteRedemption();
        redemption.setInviteId(inviteId);
        redemption.setUserEmail(normalizedEmail);
        redemption.setGroupId(invite.getGroupId());
        redemption.setRedeemedAt(Instant.now());
        redemptionRepo.save(redemption);

        int used = invite.getUsedCount() == null ? 0 : invite.getUsedCount();
        invite.setUsedCount(used + 1);
        GroupInvite saved = inviteRepo.save(invite);
        return new RedemptionResult(InviteState.OK, saved, joined, false);
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

    private static InviteState stateFor(GroupInvite invite, Instant now) {
        if (invite.getRevokedAt() != null) {
            return InviteState.REVOKED;
        }
        if (invite.getExpiresAt() != null && now.isAfter(invite.getExpiresAt())) {
            return InviteState.EXPIRED;
        }
        Integer max = invite.getMaxUses();
        Integer used = invite.getUsedCount() == null ? 0 : invite.getUsedCount();
        if (max != null && used >= max) {
            return InviteState.EXHAUSTED;
        }
        return InviteState.OK;
    }

    /**
     * {@link #validate} for a GROUP invite: a household token answers
     * NOT_FOUND here (JN-17). Households resolve through
     * {@link #previewHousehold}, which names the household only to a valid
     * token holder.
     */
    @Transactional(readOnly = true)
    public ValidationResult validateGroupInvite(String inviteId) {
        ValidationResult v = validate(inviteId);
        if (v.invite() != null && isHouseholdId(v.invite().getGroupId())) {
            return new ValidationResult(InviteState.NOT_FOUND, null);
        }
        return v;
    }

    /**
     * The state of a token someone has ALREADY redeemed. Revoked and expired
     * still refuse; exhausted does not, because this person's own use is one
     * of the uses counted.
     */
    private InviteState repeatStateFor(String inviteId) {
        GroupInvite invite = inviteRepo.findById(inviteId).orElse(null);
        if (invite == null) return InviteState.NOT_FOUND;
        if (invite.getRevokedAt() != null) return InviteState.REVOKED;
        if (invite.getExpiresAt() != null && Instant.now().isAfter(invite.getExpiresAt())) {
            return InviteState.EXPIRED;
        }
        return InviteState.OK;
    }

    private boolean isHouseholdId(String groupId) {
        if (groupId == null) return false;
        try {
            return isHousehold(groupService.getGroupByPublicId(groupId));
        } catch (RuntimeException e) {
            return false; // a missing group is refused by the join itself
        }
    }

    private static boolean isHousehold(Group group) {
        return group != null
                && HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(group.getGroupType());
    }
}
