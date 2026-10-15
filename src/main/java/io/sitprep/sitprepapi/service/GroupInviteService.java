package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupInvite;
import io.sitprep.sitprepapi.domain.GroupInviteRedemption;
import io.sitprep.sitprepapi.dto.GroupInviteLinkDto;
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
 *   <li>Admins can reset a leaked link ({@link #resetLink}).</li>
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

    /**
     * How long the group's current share link lives after it was last shared
     * (JN-10). Each {@link #currentLink} call slides the expiry out to this, so
     * a link an admin keeps sharing keeps working, and one nobody has shared
     * for a month dies by itself. 30 days is the per-mint cap the mint
     * endpoint already applies.
     */
    public static final Duration CURRENT_TTL = Duration.ofDays(30);

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
            Group group = join(inviteRepo.findById(inviteId).orElse(null), groupId, normalizedEmail);
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

        Group group = join(invite, invite.getGroupId(), normalizedEmail);

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

    /**
     * The token is the approval while the person who issued it is still an
     * owner or admin of the group (JN-20, D19): straight in through
     * {@link GroupService#joinByInvite}, seat cap enforced. A token whose
     * issuer has since stepped down (or that has no issuer on record) is a
     * request, exactly as before ({@link GroupService#selfJoin}).
     */
    private Group join(GroupInvite invite, String groupId, String email) {
        if (invite != null && issuerStillAdmin(invite, groupId)) {
            return groupService.joinByInvite(groupId, email);
        }
        return groupService.selfJoin(groupId, email);
    }

    private boolean issuerStillAdmin(GroupInvite invite, String groupId) {
        try {
            return issuerStillAdmin(invite, groupService.getGroupByPublicId(groupId));
        } catch (RuntimeException e) {
            return false; // a missing group is refused by the join itself
        }
    }

    /** The same rule against a group already in hand (the share link and its list read it per row). */
    private static boolean issuerStillAdmin(GroupInvite invite, Group g) {
        String issuer = invite.getIssuedByEmail();
        if (issuer == null || issuer.isBlank() || g == null) return false;
        return GroupRole.fromGroup(g, issuer).isAtLeastAdmin();
    }

    /**
     * Whether redeeming this token joins directly. A household token always
     * does ({@link #redeemHousehold}: the token is the admin's approval); an
     * org token only while its issuer is still an owner or admin
     * ({@link #join}). The share link and the list report THIS, so they can
     * never call a link "approving" that the redeem would treat as a request.
     */
    private static boolean approves(GroupInvite invite, Group g) {
        return isHousehold(g) || issuerStillAdmin(invite, g);
    }

    // ------------------------------------------------------------------
    // The group's one share link (JN-10, D20; B5 2026-10-14)
    // ------------------------------------------------------------------

    /**
     * The link the share sheet shows. Opening the sheet used to mint a fresh
     * unlimited token every time (and 403 for every member), so a group
     * collected live tokens nobody could see or revoke.
     *
     * <ul>
     *   <li><b>Owner/admin of an org:</b> the group's current link, reused
     *       while it is live and still approving, minted when there is none;
     *       its expiry slides out to {@link #CURRENT_TTL} on each call. A live
     *       link whose issuer has since stepped down is never handed out as
     *       current: redeeming it is only a request (JN-20), so a fresh one is
     *       minted (the old one stays live, as a request link, until it
     *       expires or a Reset revokes it).</li>
     *   <li><b>Owner/admin of a household:</b> a NEW single-use link on every
     *       call (the household rule: one person per token). Reusing one would
     *       hand the same one-person link to the spouse and then the teen, and
     *       the second would hit EXHAUSTED (B5 review 2026-10-14).</li>
     *   <li><b>Org member:</b> the plain {@code /share/group/{id}} link, no
     *       token and no write. It doesn't approve anyone: a private group
     *       still asks an admin (D20).</li>
     *   <li><b>Anyone else</b> (a household member who isn't an admin, a
     *       requester, a stranger): 403.</li>
     * </ul>
     */
    @Transactional
    public GroupInviteLinkDto currentLink(String groupId, String caller) {
        Group g = requireGroup(groupId);
        GroupRole role = GroupRole.fromGroup(g, caller);
        boolean household = isHousehold(g);
        if (!role.isAtLeastAdmin()) {
            if (!household && role == GroupRole.MEMBER) {
                return new GroupInviteLinkDto(null, "/share/group/" + g.getGroupId(),
                        false, true, false, null, null, null, null);
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ask an admin for the invite link");
        }
        Instant now = Instant.now();
        if (household) {
            return linkDto(mint(g.getGroupId(), caller, CURRENT_TTL, 1), g, true, true);
        }
        GroupInvite current = currentOf(g, now);
        if (current == null) {
            current = mint(g.getGroupId(), caller, CURRENT_TTL, null);
        } else if (current.getExpiresAt() == null || current.getExpiresAt().isBefore(now.plus(CURRENT_TTL))) {
            current.setExpiresAt(now.plus(CURRENT_TTL));
            current = inviteRepo.save(current);
        }
        return linkDto(current, g, true, true);
    }

    /**
     * "Reset link": every live link of the group's kind is revoked and one
     * new link is minted. Owner/admin only (403). Anyone holding an old link
     * gets the REVOKED answer from then on.
     */
    @Transactional
    public GroupInviteLinkDto resetLink(String groupId, String caller) {
        Group g = requireGroup(groupId);
        if (!GroupRole.fromGroup(g, caller).isAtLeastAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Group admin or owner role required");
        }
        Instant now = Instant.now();
        boolean household = isHousehold(g);
        for (GroupInvite live : inviteRepo.findActiveByGroup(g.getGroupId(), now)) {
            if (isShareLink(live, household)) {
                live.setRevokedAt(now);
                inviteRepo.save(live);
            }
        }
        GroupInvite fresh = mint(g.getGroupId(), caller, CURRENT_TTL, household ? 1 : null);
        return linkDto(fresh, g, true, true);
    }

    /**
     * The group's live links for the admin list ({@code GET /invites}), newest
     * first, the current one flagged. The caller is checked at the resource.
     */
    @Transactional(readOnly = true)
    public List<GroupInviteLinkDto> listLinks(String groupId) {
        Group g = requireGroup(groupId);
        Instant now = Instant.now();
        GroupInvite current = currentOf(g, now);
        String currentId = current == null ? null : current.getId();
        return inviteRepo.findActiveByGroup(g.getGroupId(), now).stream()
                .map(i -> linkDto(i, g, i.getId().equals(currentId), true))
                .toList();
    }

    /**
     * The newest live share link of the group's kind that still approves
     * ({@link #approves}), or null. A link from a former admin is skipped: it
     * would be handed out as the group's link while every redeem of it lands
     * in the request queue.
     */
    private GroupInvite currentOf(Group g, Instant now) {
        boolean household = isHousehold(g);
        return inviteRepo.findActiveByGroup(g.getGroupId(), now).stream() // newest first
                .filter(i -> isShareLink(i, household))
                .filter(i -> approves(i, g))
                .findFirst()
                .orElse(null);
    }

    /** Org share links are unlimited; household ones are single-use. */
    private static boolean isShareLink(GroupInvite i, boolean household) {
        return household ? Integer.valueOf(1).equals(i.getMaxUses()) : i.getMaxUses() == null;
    }

    private static GroupInviteLinkDto linkDto(GroupInvite i, Group g, boolean current, boolean canReset) {
        String path = (isHousehold(g) ? "/share/household/" : "/share/i/") + i.getId();
        return new GroupInviteLinkDto(i.getId(), path, approves(i, g), current, canReset,
                i.getIssuedAt(), i.getExpiresAt(), i.getMaxUses(),
                i.getUsedCount() == null ? 0 : i.getUsedCount());
    }

    private Group requireGroup(String groupId) {
        try {
            return groupService.getGroupByPublicId(groupId);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
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
