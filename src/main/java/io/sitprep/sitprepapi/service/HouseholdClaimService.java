package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.domain.EmergencySupportAssignment;
import io.sitprep.sitprepapi.domain.EmergencySupportProfile;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdAccompaniment;
import io.sitprep.sitprepapi.domain.HouseholdClaimInvite;
import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import io.sitprep.sitprepapi.domain.HouseholdMemberBand;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.DtoImages;
import io.sitprep.sitprepapi.repo.EmergencyContactRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportAssignmentRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportProfileRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdAccompanimentRepo;
import io.sitprep.sitprepapi.repo.HouseholdClaimInviteRepo;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.repo.HouseholdMemberBandRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.GroupNotificationRecipients;
import io.sitprep.sitprepapi.util.GroupUrlUtil;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * "Claim your spot" — a person a household added by hand (a manual member)
 * signs in through a link and becomes that person on the roster
 * (household roster EXEC-B, 2026-10-07).
 *
 * <p><b>Mint</b> (admin): a single-use, expiring token bound to ONE manual
 * member. Minting again while a live link exists returns that link (so a
 * double tap can't spray links); an expired one is revoked and replaced. At
 * most {@value #MAX_MINTS_PER_DAY} new links per household per 24h → 429.</p>
 *
 * <p><b>Resolve</b> (public): household name, the manual member's name and
 * band, the inviter's FIRST name. No emails, no ids beyond the token itself.</p>
 *
 * <p><b>Accept</b> (signed in), ONE transaction:</p>
 * <ol>
 *   <li>the caller becomes a household member — the admin-issued link is the
 *       approval; already a member is fine (see below);</li>
 *   <li>their membership keeps the manual member's band
 *       ({@code household_member_band});</li>
 *   <li>every reference moves from {@code manual:<id>} to {@code user:<email>}:
 *       accompaniments (both sides), the emergency-support profile, support
 *       assignments, and emergency contacts made for that person. On a
 *       collision with a row the account already has, the ACCOUNT's row is
 *       kept and the manual one dropped; a self-reference (accompanying
 *       yourself) is dropped;</li>
 *   <li>the manual member is deleted;</li>
 *   <li>counts are unchanged — the slot moves, it isn't added. Exception: a
 *       caller who was ALREADY a member was counted twice (once as the
 *       account, once as the manual row), so their former band loses one,
 *       never below named;</li>
 *   <li>base household: set to this one when the caller has none, or when
 *       theirs is a solo household with nothing in it (what provisioning
 *       auto-creates at sign-up). A base with any data is left alone — never
 *       strand a plan; they can switch themselves;</li>
 *   <li>the token is consumed; roster/manual/accompaniment frames go out and
 *       the household's admins get a presence-aware {@code new_member}
 *       notice after commit.</li>
 * </ol>
 *
 * <p><b>Who may accept.</b> Anyone signed in holding the link — the token is
 * the capability, as with household invites. That includes a caller who is
 * already a member (the link asserts "that entry is me" — the de-dup case,
 * e.g. an owner who added themselves by hand before signing up) and the
 * inviter themselves. Re-accepting a consumed link with the account that
 * consumed it returns the same result (idempotent); any other account gets
 * 410 CONSUMED.</p>
 */
@Service
public class HouseholdClaimService {

    private static final Logger log = LoggerFactory.getLogger(HouseholdClaimService.class);

    public static final Duration TTL = Duration.ofDays(7);
    public static final int MAX_MINTS_PER_DAY = 20;
    public static final String SHARE_PATH_PREFIX = "/claim/";

    private static final SecureRandom RANDOM = new SecureRandom();

    public enum State { OK, NOT_FOUND, EXPIRED, REVOKED, CONSUMED, MEMBER_GONE }

    public record ClaimInvite(String token, String sharePath, String householdId, String manualMemberId,
                              Instant issuedAt, Instant expiresAt, boolean reused) {}

    public record Preview(State state, String householdName, String memberName, HouseholdBand band,
                          String inviterFirstName, Instant expiresAt) {}

    public record AcceptResult(State state, boolean alreadyClaimed, String householdId, HouseholdBand band,
                               String claimedName, String baseHouseholdId, boolean baseChanged) {}

    /** Accept/resolve failure carrying the wire state (404 / 410). */
    public static class ClaimStateException extends ResponseStatusException {
        private final State state;

        public ClaimStateException(State state) {
            super(state == State.NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.GONE, state.name());
            this.state = state;
        }

        public State state() { return state; }
    }

    private final HouseholdClaimInviteRepo claimRepo;
    private final HouseholdManualMemberRepo manualRepo;
    private final GroupRepo groupRepo;
    private final GroupService groupService;
    private final HouseholdAccessService access;
    private final HouseholdMemberBandRepo bandRepo;
    private final HouseholdAccompanimentRepo accompanimentRepo;
    private final HouseholdAccompanimentService accompanimentService;
    private final EmergencySupportProfileRepo supportProfileRepo;
    private final EmergencySupportAssignmentRepo supportAssignmentRepo;
    private final EmergencyContactRepo contactRepo;
    private final UserInfoRepo userInfoRepo;
    private final HouseholdCompositionService composition;
    private final WebSocketMessageSender ws;
    private final NotificationService notificationService;
    private final HouseholdBaseDataProbe baseData;

    public HouseholdClaimService(HouseholdClaimInviteRepo claimRepo,
                                 HouseholdManualMemberRepo manualRepo,
                                 GroupRepo groupRepo,
                                 GroupService groupService,
                                 HouseholdAccessService access,
                                 HouseholdMemberBandRepo bandRepo,
                                 HouseholdAccompanimentRepo accompanimentRepo,
                                 HouseholdAccompanimentService accompanimentService,
                                 EmergencySupportProfileRepo supportProfileRepo,
                                 EmergencySupportAssignmentRepo supportAssignmentRepo,
                                 EmergencyContactRepo contactRepo,
                                 UserInfoRepo userInfoRepo,
                                 HouseholdCompositionService composition,
                                 WebSocketMessageSender ws,
                                 NotificationService notificationService,
                                 HouseholdBaseDataProbe baseData) {
        this.claimRepo = claimRepo;
        this.manualRepo = manualRepo;
        this.groupRepo = groupRepo;
        this.groupService = groupService;
        this.access = access;
        this.bandRepo = bandRepo;
        this.accompanimentRepo = accompanimentRepo;
        this.accompanimentService = accompanimentService;
        this.supportProfileRepo = supportProfileRepo;
        this.supportAssignmentRepo = supportAssignmentRepo;
        this.contactRepo = contactRepo;
        this.userInfoRepo = userInfoRepo;
        this.composition = composition;
        this.ws = ws;
        this.notificationService = notificationService;
        this.baseData = baseData;
    }

    // ── mint / revoke ───────────────────────────────────────────────────

    @Transactional
    public ClaimInvite mint(String householdId, String manualMemberId, String caller) {
        Group g = household(householdId);
        if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found");
        access.requireCanAdminHousehold(caller, householdId);
        HouseholdManualMember m = manualRepo.findById(manualMemberId)
                .filter(x -> householdId.equals(x.getHouseholdId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));

        Instant now = Instant.now();
        for (HouseholdClaimInvite open : claimRepo.findOpenForMember(m.getId())) {
            if (open.isLive(now)) return toInvite(open, true);
            open.setRevokedAt(now); // expired-but-open: retire it so the live index admits a new one
            claimRepo.save(open);
        }
        claimRepo.flush();

        if (claimRepo.countByHouseholdIdAndIssuedAtAfter(householdId, now.minus(Duration.ofHours(24)))
                >= MAX_MINTS_PER_DAY) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many claim links today for this household. Try again tomorrow.");
        }

        HouseholdClaimInvite inv = new HouseholdClaimInvite();
        inv.setToken(newToken());
        inv.setHouseholdId(householdId);
        inv.setManualMemberId(m.getId());
        inv.setIssuedByEmail(lower(caller));
        inv.setIssuedAt(now);
        inv.setExpiresAt(now.plus(TTL));
        return toInvite(claimRepo.save(inv), false);
    }

    /** Revoke every open link for a manual member (admin). Idempotent. */
    @Transactional
    public void revoke(String householdId, String manualMemberId, String caller) {
        if (household(householdId) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found");
        access.requireCanAdminHousehold(caller, householdId);
        Instant now = Instant.now();
        for (HouseholdClaimInvite open : claimRepo.findOpenForMember(manualMemberId)) {
            if (!householdId.equals(open.getHouseholdId())) continue;
            open.setRevokedAt(now);
            claimRepo.save(open);
        }
    }

    // ── resolve (public) ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Preview resolve(String token) {
        HouseholdClaimInvite inv = token == null ? null : claimRepo.findById(token).orElse(null);
        if (inv == null) return emptyPreview(State.NOT_FOUND);
        State s = stateOf(inv, Instant.now());
        if (s != State.OK) return emptyPreview(s);
        Group g = household(inv.getHouseholdId());
        HouseholdManualMember m = manualRepo.findById(inv.getManualMemberId())
                .filter(x -> inv.getHouseholdId().equals(x.getHouseholdId())).orElse(null);
        if (g == null || m == null) return emptyPreview(State.MEMBER_GONE);
        String inviterFirst = userInfoRepo.findByUserEmailIgnoreCase(inv.getIssuedByEmail())
                .map(UserInfo::getUserFirstName).map(String::trim).filter(x -> !x.isEmpty()).orElse(null);
        String hhName = g.getGroupName() == null || g.getGroupName().isBlank() ? null : g.getGroupName().trim();
        return new Preview(State.OK, hhName, m.getName(), m.effectiveBand(), inviterFirst, inv.getExpiresAt());
    }

    // ── accept ──────────────────────────────────────────────────────────

    @Transactional
    public AcceptResult accept(String token, String callerEmail) {
        String email = lower(callerEmail);
        if (email == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        HouseholdClaimInvite inv = token == null ? null : claimRepo.findByTokenForUpdate(token).orElse(null);
        if (inv == null) throw new ClaimStateException(State.NOT_FOUND);

        Instant now = Instant.now();
        if (inv.getConsumedAt() != null) {
            if (email.equals(lower(inv.getConsumedByEmail()))) return alreadyClaimed(inv, email);
            throw new ClaimStateException(State.CONSUMED);
        }
        State s = stateOf(inv, now);
        if (s != State.OK) throw new ClaimStateException(s);

        String hid = inv.getHouseholdId();
        Group g = household(hid);
        HouseholdManualMember m = manualRepo.findById(inv.getManualMemberId())
                .filter(x -> hid.equals(x.getHouseholdId())).orElse(null);
        if (g == null || m == null) throw new ClaimStateException(State.MEMBER_GONE);

        HouseholdBand band = m.effectiveBand();
        String manualId = m.getId();
        String manualName = m.getName();
        boolean wasMember = HouseholdCompositionService.accountEmails(g).contains(email);
        HouseholdBand previousBand = !wasMember ? null
                : bandRepo.findById(new HouseholdMemberBand.Key(hid, email))
                        .map(HouseholdMemberBand::getBand).orElse(HouseholdBand.ADULT);

        // 1 — membership (trusted: the admin issued the link).
        boolean added = groupService.joinHouseholdByClaim(hid, email);

        // 2 — the slot keeps its band.
        HouseholdMemberBand row = bandRepo.findById(new HouseholdMemberBand.Key(hid, email))
                .orElseGet(HouseholdMemberBand::new);
        row.setHouseholdId(hid);
        row.setUserEmail(email);
        row.setBand(band);
        bandRepo.save(row);

        // 3 — every reference moves to the account.
        UserInfo caller = userInfoRepo.findByUserEmailIgnoreCase(email).orElse(null);
        String callerName = displayName(caller);
        migrateReferences(hid, manualId, email, callerName);

        // 4 — the manual row goes.
        manualRepo.delete(m);
        manualRepo.flush();

        // 5 — counts: unchanged, except the double-count a member's de-dup removes.
        if (wasMember && previousBand != null) composition.lowerBand(hid, previousBand);
        composition.raiseToNamed(hid, false, null); // guard; a no-op when the invariant held

        // 6 — base household.
        String baseBefore = caller == null ? null : caller.getBaseHouseholdId();
        String baseAfter = baseBefore;
        if (caller != null && !hid.equals(baseBefore)
                && (baseBefore == null || baseBefore.isBlank() || baseData.isDisposableSolo(baseBefore, email))) {
            caller.setBaseHouseholdId(hid);
            userInfoRepo.save(caller);
            baseAfter = hid;
        }

        // 7 — consume, broadcast, notify.
        inv.setConsumedAt(now);
        inv.setConsumedByEmail(email);
        claimRepo.save(inv);

        afterCommit(() -> {
            ws.sendHouseholdManualMemberDeletion(hid, manualId);
            ws.sendHouseholdAccompanimentReplaceAll(hid, accompanimentService.list(hid));
        });
        notifyAdmins(g, email, caller, callerName, manualName);

        log.info("household claim accepted household={} manual={} added={} wasMember={} baseChanged={}",
                hid, manualId, added, wasMember, !Objects.equals(baseBefore, baseAfter));
        return new AcceptResult(State.OK, false, hid, band, manualName, baseAfter,
                !Objects.equals(baseBefore, baseAfter));
    }

    /**
     * manual:{id} → user:{email} on every table that names a household subject.
     * Package-private for the reference-migration tests.
     */
    void migrateReferences(String hid, String manualId, String email, String callerName) {
        // Accompaniments — both sides.
        List<HouseholdAccompaniment> rows = accompanimentRepo.findByHouseholdId(hid);
        boolean userAlreadyAccompanied = rows.stream()
                .anyMatch(a -> "user".equals(a.getAccompaniedKind()) && email.equalsIgnoreCase(a.getAccompaniedId()));
        for (HouseholdAccompaniment a : rows) {
            boolean accompaniedIsManual = "manual".equals(a.getAccompaniedKind()) && manualId.equals(a.getAccompaniedId());
            boolean supervisorIsManual = "manual".equals(a.getSupervisorKind()) && manualId.equals(a.getSupervisorId());
            if (!accompaniedIsManual && !supervisorIsManual) continue;
            if (accompaniedIsManual) {
                boolean selfSupervised = "user".equals(a.getSupervisorKind()) && email.equalsIgnoreCase(a.getSupervisorId());
                if (userAlreadyAccompanied || selfSupervised) { accompanimentRepo.delete(a); continue; }
                a.setAccompaniedKind("user");
                a.setAccompaniedId(email);
                // Confirmed on their behalf while they were a manual member; keep it confirmed.
                a.setPending(false);
            }
            if (supervisorIsManual) {
                boolean selfAccompanied = "user".equals(a.getAccompaniedKind()) && email.equalsIgnoreCase(a.getAccompaniedId());
                if (selfAccompanied) { accompanimentRepo.delete(a); continue; }
                a.setSupervisorKind("user");
                a.setSupervisorId(email);
            }
            accompanimentRepo.save(a);
        }

        // Emergency-support profile — the account's own row wins a collision.
        supportProfileRepo.findByHouseholdIdAndSubjectTypeAndSubjectId(hid, "manual", manualId).ifPresent(p -> {
            boolean userHasOne = supportProfileRepo
                    .findByHouseholdIdAndSubjectTypeAndSubjectId(hid, "user", email).isPresent();
            if (userHasOne) {
                supportProfileRepo.delete(p);
            } else {
                p.setSubjectType("user");
                p.setSubjectId(email);
                supportProfileRepo.save(p);
            }
        });

        // Support assignments — per role, same rule.
        for (EmergencySupportAssignment.Role role : EmergencySupportAssignment.Role.values()) {
            supportAssignmentRepo.findByHouseholdIdAndSubjectTypeAndSubjectIdAndRole(hid, "manual", manualId, role)
                    .ifPresent(a -> {
                        boolean userHasOne = supportAssignmentRepo
                                .findByHouseholdIdAndSubjectTypeAndSubjectIdAndRole(hid, "user", email, role).isPresent();
                        if (userHasOne) {
                            supportAssignmentRepo.delete(a);
                        } else {
                            a.setSubjectType("user");
                            a.setSubjectId(email);
                            supportAssignmentRepo.save(a);
                        }
                    });
        }

        // Emergency contacts made FOR this person.
        accompanimentRepo.flush();
        contactRepo.reassignManualSubject(manualId, email, callerName);
    }

    // ── internals ───────────────────────────────────────────────────────

    private AcceptResult alreadyClaimed(HouseholdClaimInvite inv, String email) {
        String hid = inv.getHouseholdId();
        HouseholdBand band = bandRepo.findById(new HouseholdMemberBand.Key(hid, email))
                .map(HouseholdMemberBand::getBand).orElse(HouseholdBand.ADULT);
        String base = userInfoRepo.findByUserEmailIgnoreCase(email).map(UserInfo::getBaseHouseholdId).orElse(null);
        return new AcceptResult(State.OK, true, hid, band, null, base, false);
    }

    private void notifyAdmins(Group g, String claimerEmail, UserInfo claimer, String claimerName, String manualName) {
        if (notificationService == null) return;
        List<String> admins = GroupNotificationRecipients.adminOwnerEmails(g);
        String first = claimer == null || claimer.getUserFirstName() == null || claimer.getUserFirstName().isBlank()
                ? "Someone" : claimer.getUserFirstName().trim();
        String hhName = g.getGroupName() == null || g.getGroupName().isBlank() ? "your household" : g.getGroupName().trim();
        boolean sameName = manualName != null && (manualName.trim().equalsIgnoreCase(first)
                || (claimerName != null && manualName.trim().equalsIgnoreCase(claimerName)));
        String body = sameName
                ? first + " joined and claimed their spot."
                : first + " joined and claimed the spot you added as " + manualName + ".";
        String title = first + " joined " + hhName;
        String url = GroupUrlUtil.getGroupTargetUrl(g);
        String icon = claimer == null ? null : DtoImages.avatar(claimer.getProfileImageUrl());
        String actorId = claimer == null ? null : claimer.getId();
        String gid = g.getGroupId();
        afterCommit(() -> {
            for (String admin : admins) {
                if (admin == null || admin.equalsIgnoreCase(claimerEmail)) continue;
                try {
                    String token = userInfoRepo.findByUserEmailIgnoreCase(admin).map(UserInfo::getFcmtoken).orElse(null);
                    notificationService.deliverPresenceAware(admin, title, body, hhName, icon,
                            "new_member", gid, url, null, token, actorId);
                } catch (Exception e) {
                    log.warn("claim notice to {} failed: {}", admin, e.getMessage());
                }
            }
        });
    }

    private static State stateOf(HouseholdClaimInvite inv, Instant now) {
        if (inv.getConsumedAt() != null) return State.CONSUMED;
        if (inv.getRevokedAt() != null) return State.REVOKED;
        if (inv.getExpiresAt() == null || !now.isBefore(inv.getExpiresAt())) return State.EXPIRED;
        return State.OK;
    }

    private static Preview emptyPreview(State s) {
        return new Preview(s, null, null, null, null, null);
    }

    private static ClaimInvite toInvite(HouseholdClaimInvite i, boolean reused) {
        return new ClaimInvite(i.getToken(), SHARE_PATH_PREFIX + i.getToken(), i.getHouseholdId(),
                i.getManualMemberId(), i.getIssuedAt(), i.getExpiresAt(), reused);
    }

    private Group household(String hid) {
        if (hid == null || hid.isBlank()) return null;
        return groupRepo.findByGroupId(hid)
                .filter(x -> HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(x.getGroupType()))
                .orElse(null);
    }

    private static String newToken() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String displayName(UserInfo u) {
        if (u == null) return null;
        String n = ((u.getUserFirstName() == null ? "" : u.getUserFirstName().trim()) + " "
                + (u.getUserLastName() == null ? "" : u.getUserLastName().trim())).trim();
        return n.isEmpty() ? null : n;
    }

    private static String lower(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }
}
