package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.constant.PlanTier;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.dto.CheckInRollupDto;
import io.sitprep.sitprepapi.dto.DtoImages;
import io.sitprep.sitprepapi.dto.GroupAlertFrame;
import io.sitprep.sitprepapi.dto.GroupMembershipFrame;
import io.sitprep.sitprepapi.dto.GroupMembershipActionResultDto;
import io.sitprep.sitprepapi.util.GroupUrlUtil;
import io.sitprep.sitprepapi.util.GroupNotificationRecipients;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private static final Logger logger = LoggerFactory.getLogger(GroupService.class);

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final WebSocketMessageSender webSocketMessageSender;
    private final HouseholdEventService householdEventService;
    private NotificationService notificationService; // setter-injected
    private final CheckInRequestService checkInRequestService;

    /** How long a check-in runs before it ends by itself (the decay window). */
    @org.springframework.beans.factory.annotation.Value("${app.groupAlert.decayHours:48}")
    private int checkInHours = 48;

    public GroupService(GroupRepo groupRepo,
                        UserInfoRepo userInfoRepo,
                        WebSocketMessageSender webSocketMessageSender,
                        HouseholdEventService householdEventService,
                        NotificationService notificationService,
                        CheckInRequestService checkInRequestService) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.webSocketMessageSender = webSocketMessageSender;
        this.householdEventService = householdEventService;
        this.notificationService = notificationService;
        this.checkInRequestService = checkInRequestService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    // Setter-injected (household roster EXEC-B) so the existing constructor —
    // and every test that builds this service by hand — stays unchanged. Null
    // in those tests: the hooks below are no-ops without them.
    private HouseholdCompositionService householdComposition;
    private HouseholdProvisioningService householdProvisioning;
    private io.sitprep.sitprepapi.repo.HouseholdMemberBandRepo householdMemberBandRepo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHouseholdComposition(HouseholdCompositionService householdComposition) {
        this.householdComposition = householdComposition;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHouseholdProvisioning(HouseholdProvisioningService householdProvisioning) {
        this.householdProvisioning = householdProvisioning;
    }

    // Setter-injected for the same reason: the check-in rollup counts a
    // household's manual members (gameplan §5.5). Null in hand-built tests.
    private HouseholdManualMemberService manualMembers;
    private HouseholdAccompanimentService accompaniments;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setManualMembers(HouseholdManualMemberService manualMembers) {
        this.manualMembers = manualMembers;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAccompaniments(HouseholdAccompanimentService accompaniments) {
        this.accompaniments = accompaniments;
    }

    private EmergencySupportService supportService;

    // Live Activity pushes (live_activities P1). Setter-injected so every test
    // that builds this service by hand stays unchanged; null there, and every
    // hook below is a no-op without it.
    private LiveActivityPushService liveActivities;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLiveActivities(LiveActivityPushService liveActivities) {
        this.liveActivities = liveActivities;
    }

    // BE-13: drops a removed account's live socket subscriptions to the group.
    // Setter-injected and optional like the hooks above; null in hand-built tests.
    private io.sitprep.sitprepapi.websocket.GroupTopicEvictor topicEvictor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTopicEvictor(io.sitprep.sitprepapi.websocket.GroupTopicEvictor topicEvictor) {
        this.topicEvictor = topicEvictor;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSupportService(EmergencySupportService supportService) {
        this.supportService = supportService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHouseholdMemberBandRepo(io.sitprep.sitprepapi.repo.HouseholdMemberBandRepo repo) {
        this.householdMemberBandRepo = repo;
    }

    /**
     * An account joined a household: fill an ADULT placeholder if one is open,
     * else raise adults — i.e. raise the demographic to its named totals. Never
     * creates a demographic row (joining is not answering "who are you planning
     * for?"). No-op for non-households and when the plan has no row yet.
     */
    private void syncHouseholdCountsAfterJoin(Group g) {
        if (householdComposition == null || g == null) return;
        if (!HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType())) return;
        householdComposition.raiseToNamed(g.getGroupId(), false, null);
    }

    /**
     * Create a group from the fields a creator may choose (JN-01).
     *
     * <p>Everything else is the server's: a minted id and join code, the
     * caller as the only owner, admin and member, FREE, no check-in running,
     * no links, no billing state. A household is always Private (it is never
     * listed publicly). A body {@code groupId} that names an existing group is
     * a 409 and nothing is written: that is the merge-takeover shape, and a
     * loud refusal beats quietly minting a second group next to it.</p>
     */
    @Transactional
    public Group createGroup(io.sitprep.sitprepapi.dto.CreateGroupRequest req, String caller) {
        if (caller == null || caller.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to create a group");
        }
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Group details required");
        }
        String claimedId = req.groupId() == null ? null : req.groupId().trim();
        if (claimedId != null && !claimedId.isEmpty() && groupRepo.existsById(claimedId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That group already exists");
        }
        String name = req.groupName() == null ? "" : req.groupName().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the group a name");
        }
        if (name.length() > GROUP_NAME_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Group names can be up to " + GROUP_NAME_MAX + " characters");
        }
        String type = req.groupType() == null ? "" : req.groupType().trim();
        if (type.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a group type");
        }
        boolean household = HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(type);
        String me = caller.trim().toLowerCase(Locale.ROOT);
        Instant now = Instant.now();

        Group g = new Group();
        g.setGroupId(UUID.randomUUID().toString());
        g.setGroupCode(mintGroupCode(household));
        g.setGroupName(name);
        g.setGroupType(household ? HouseholdEventService.HOUSEHOLD_GROUP_TYPE : type);
        g.setPrivacy(!household && "public".equalsIgnoreCase(trimToNull(req.privacy())) ? "Public" : "Private");
        g.setDescription(trimToNull(req.description()));
        g.setAddress(trimToNull(req.address()));
        g.setZipCode(trimToNull(req.zipCode()));
        g.setLatitude(req.latitude());
        g.setLongitude(req.longitude());
        g.setLogoImageUrl(trimToNull(req.logoImageUrl()));
        g.setBusinessCategory(trimToNull(req.businessCategory()));
        g.setWebsiteUrl(trimToNull(req.websiteUrl()));

        g.setOwnerEmail(me);
        g.setOwnerName(userInfoRepo.findByUserEmailIgnoreCase(me)
                .map(u -> trimToNull((nullToEmpty(u.getUserFirstName()) + " " + nullToEmpty(u.getUserLastName())).trim()))
                .orElse(null));
        g.setAdminEmails(new ArrayList<>(List.of(me)));
        g.setMemberEmails(new ArrayList<>(List.of(me)));
        g.setPendingMemberEmails(new ArrayList<>());
        g.setSubGroupIDs(new ArrayList<>());
        g.setParentGroupIDs(new ArrayList<>());
        g.setMemberCount(1);
        g.setAlert("Not Active");
        g.setPlanTier(PlanTier.FREE.name());
        g.setAgencyAuthorized(false);
        g.setLastUpdatedBy(me);
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        return createGroup(g);
    }

    /** A join code nobody holds. Households keep their "HH-" prefix. */
    private String mintGroupCode(boolean household) {
        for (int i = 0; i < 8; i++) {
            String raw = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String code = household ? "HH-" + raw.toUpperCase(Locale.ROOT) : "g-" + raw;
            if (!groupRepo.existsByGroupCodeIgnoreCase(code)) return code;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Couldn't create the group. Try again.");
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Persist an already-built group. Internal: the only caller is
     * {@link #createGroup(io.sitprep.sitprepapi.dto.CreateGroupRequest, String)},
     * which builds every field server-side.
     */
    private Group createGroup(Group group) {
        if (group.getGroupId() == null || group.getGroupId().isEmpty()) {
            throw new IllegalStateException("createGroup needs a minted groupId");
        }
        Group saved = groupRepo.save(group);
        // Creating a household seeds the creator's base household if they
        // don't have one yet — so a brand-new user's first household becomes
        // their base immediately, without waiting for the boot-time backfill.
        if ("Household".equalsIgnoreCase(saved.getGroupType())) {
            String owner = saved.getOwnerEmail();
            if (owner != null && !owner.isBlank()) {
                userInfoRepo.findByUserEmailIgnoreCase(owner).ifPresent(u -> {
                    if (u.getBaseHouseholdId() == null || u.getBaseHouseholdId().isBlank()) {
                        u.setBaseHouseholdId(saved.getGroupId());
                        userInfoRepo.save(u);
                    }
                });
            }
        }
        return saved;
    }

    public boolean isGroupCodeTaken(String code) {
        if (code == null || code.isBlank()) return false;
        return groupRepo.existsByGroupCodeIgnoreCase(code);
    }

    public Group getGroupByPublicId(String groupId) {
        return groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found for groupId: " + groupId));
    }

    /**
     * Send a non-emergency "please check in" ping to a group's members
     * without flipping the group's alert state. Phase 1 of
     * {@code SitPrep FE docs/guidelines/product/BUSINESS_MODEL.md} — the family check-in primitive.
     *
     * <p>Authorization: the caller must be a member of the group. For
     * <b>household</b> groups any member can ping (it's the family —
     * "hey everyone check in" is benign). For non-household groups
     * (school / business / neighborhood) it's restricted to
     * admins/owners so a single member can't blast a 500-person org.</p>
     *
     * <p>Throws {@link SecurityException} on an authorization failure —
     * the resource layer maps that to HTTP 403.</p>
     *
     * <p><b>Households only</b> (gameplan §5.1, 2026-10-08): the caller is not
     * recorded as asked — nobody asks themselves — and one ask per household
     * per {@link HouseholdEventService#ASK_EVERYONE_COOLDOWN}, from any member.
     * A second ask inside it sends nothing and returns who sent the first.
     * Every other group type behaves exactly as before.</p>
     *
     * <p>Deliberately NOT {@code @Transactional}: {@code recordAsked} swallows
     * its own failures, and joining one outer transaction would turn a
     * swallowed bookkeeping error into a rollback of the ask itself.</p>
     */
    public AskEveryoneResult requestCheckIn(String groupId, String callerEmail) {
        if (callerEmail == null || callerEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to request a check-in");
        }
        Group group = getGroupByPublicId(groupId);
        String me = callerEmail.trim().toLowerCase();

        if (!MemberActionPolicy.onRoster(group, me)) {
            throw new SecurityException("Only members can request a check-in");
        }
        // Household: any member; other groups: owner or admin. The same rule
        // the member-view's viewerCapabilities.askEveryone reports.
        if (!MemberActionPolicy.canAskEveryone(group, me)) {
            throw new SecurityException(
                    "Only admins can request a check-in for this group");
        }

        boolean household = HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(group.getGroupType());
        if (household) {
            Optional<io.sitprep.sitprepapi.domain.HouseholdEvent> prior =
                    householdEventService.activeCheckInRequest(group.getGroupId());
            if (prior.isPresent()) {
                io.sitprep.sitprepapi.domain.HouseholdEvent first = prior.get();
                String byEmail = first.getActorEmail();
                String byName = byEmail == null ? null : userInfoRepo.findByUserEmailIgnoreCase(byEmail)
                        .map(UserInfo::getUserFirstName)
                        .filter(n -> n != null && !n.isBlank())
                        .map(String::trim)
                        .orElse(null);
                return AskEveryoneResult.coolingDown(byEmail, byName, first.getAt(),
                        first.getAt().plus(HouseholdEventService.ASK_EVERYONE_COOLDOWN));
            }
        }

        String callerName = userInfoRepo.findByUserEmailIgnoreCase(callerEmail)
                .map(UserInfo::getUserFirstName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);

        // Nobody asks themselves — in a household. Org groups keep recording
        // the whole roster, as they always have.
        List<String> asked = household
                ? safeList(group.getMemberEmails()).stream()
                        .filter(e -> e != null && !e.trim().equalsIgnoreCase(me))
                        .toList()
                : group.getMemberEmails();

        // RECORD THE ASK BEFORE SENDING IT (RC-2).
        //
        // The notification is the part that can be dropped -- a muted category
        // hits Lane.DROP and writes nothing anywhere -- so if the record were a
        // side effect of delivery, the people most likely to be missed would be
        // the ones with no evidence they were ever asked. Recording first means
        // "asked" survives every delivery outcome, which is the distinction the
        // roster needs: never asked is not the same as asked and silent.
        checkInRequestService.recordAsked(group, asked, callerEmail);
        if (household) {
            householdEventService.recordCheckInRequest(group.getGroupId(), me,
                    asked.stream().map(e -> e.trim().toLowerCase(Locale.ROOT)).toList(),
                    callerName == null ? null : callerName.trim());
        }

        notificationService.notifyCheckInRequest(group, callerEmail, callerName);
        return AskEveryoneResult.SENT;
    }

    /**
     * Outcome of "Ask everyone". {@code sent=false} only for a household inside
     * its cooldown; the other fields then name the ask that is still standing,
     * so the client can say "Maya asked everyone · 3m ago" instead of failing.
     */
    public record AskEveryoneResult(boolean sent,
                                    String requestedByEmail,
                                    String requestedByName,
                                    Instant requestedAt,
                                    Instant retryAt) {
        public static final AskEveryoneResult SENT = new AskEveryoneResult(true, null, null, null, null);

        static AskEveryoneResult coolingDown(String byEmail, String byName, Instant at, Instant retryAt) {
            return new AskEveryoneResult(false, byEmail, byName, at, retryAt);
        }
    }

    @Transactional(readOnly = true)
    public CheckInRollupDto getCheckInRollup(String groupId) {
        Group group = getGroupByPublicId(groupId);
        return buildCheckInRollup(group);
    }

    /**
     * The same rollup for a group already in hand — the scheduled reminder's
     * tally reads it, so the push says what /check-in-rollup and the drawer
     * say (manual members included).
     */
    public CheckInRollupDto checkInRollupFor(Group group) {
        return buildCheckInRollup(group);
    }

    /**
     * Nudge only members who have not checked in since the current active
     * alert started. This is the targeted follow-up behind the admin rollup
     * card: first review who is missing, then ping only the missing people.
     */
    @Transactional
    public CheckInRollupDto pingMissingCheckIns(String groupId, String callerEmail) {
        Group group = getGroupByPublicId(groupId);
        CheckInRollupDto rollup = buildCheckInRollup(group);
        if (!rollup.active() || rollup.missing() <= 0) return rollup;

        String callerName = userInfoRepo.findByUserEmailIgnoreCase(callerEmail)
                .map(UserInfo::getUserFirstName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("Your group admin");
        nudgeMissing(group, rollup, callerEmail, callerName);
        return rollup;
    }

    /**
     * The scheduled follow-up to the people who haven't answered (ask-to-check-in
     * plan C-3): the same nudge an organizer's "ask everyone who hasn't
     * answered" sends, with the same 10-minute cooldown and the same record of
     * who was asked, signed by the group rather than a person. Returns how many
     * were reminded.
     */
    @Transactional
    public int remindMissing(Group group) {
        CheckInRollupDto rollup = buildCheckInRollup(group);
        if (!rollup.active() || rollup.missing() <= 0) return 0;
        String sender = HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(group.getGroupType())
                ? "Your household"
                : (group.getGroupName() != null && !group.getGroupName().isBlank() ? group.getGroupName() : "Your group");
        return nudgeMissing(group, rollup, null, sender);
    }

    /** Nudge every member the rollup counts as missing; the recorder owns the cooldown. */
    private int nudgeMissing(Group group, CheckInRollupDto rollup, String senderEmail, String senderName) {
        Set<String> missingEmails = rollup.members().stream()
                .filter(m -> !m.accounted())
                .map(CheckInRollupDto.Member::email)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (missingEmails.isEmpty()) return 0;

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(new ArrayList<>(missingEmails));
        int pinged = 0;
        for (UserInfo user : users) {
            if (user.getUserEmail() == null) continue;
            // Record FIRST: the recorder owns the cooldown, and it returning
            // false is the only thing standing between "Nudge" and a push that
            // bypasses quiet hours on every tap.
            if (!householdEventService.recordNudge(
                    group.getGroupId(), senderEmail, user.getUserEmail())) {
                continue;
            }
            pushNudge(group, user, senderName);
            // Only the people actually nudged are recorded as asked: the
            // cooldown above skips the rest, and a skipped nudge is not an ask.
            checkInRequestService.recordAsked(
                    group, java.util.List.of(user.getUserEmail()), senderEmail);
            pinged++;
        }
        logger.info("Pinged {} of {} missing check-in member(s) for group {}",
                pinged, users.size(), group.getGroupId());
        return pinged;
    }

    /**
     * Keep a running check-in going for another window WITHOUT restarting it
     * (ask-to-check-in plan C-4). The start stays put, so every answer given so
     * far still counts; only the automatic end moves. The "auto-ends in 12
     * hours" reminder re-arms against the new end. Organizers only (checked by
     * the resource). Returns the new end, or throws when nothing is running.
     */
    @Transactional
    public Instant continueCheckIn(String groupId, String callerEmail) {
        Group group = getGroupByPublicId(groupId);
        if (!"Active".equalsIgnoreCase(group.getAlert()) || group.getAlertActivatedAt() == null) {
            throw new IllegalStateException("There is no check-in running to continue");
        }
        Instant until = Instant.now().plus(java.time.Duration.ofHours(checkInHours));
        group.setAlertExpiresAt(until);
        Integer fired = group.getCheckInRemindersFired();
        if (fired != null && fired > GroupCheckInReminderService.ENDING_SOON_SLOT) {
            group.setCheckInRemindersFired(GroupCheckInReminderService.ENDING_SOON_SLOT);
        }
        Group saved = groupRepo.save(group);
        // Every row's window (`checkIn.showUntil`) just moved, with no status
        // written — the same frame a start or an end sends, so open rosters
        // re-read instead of lapsing members at the old end.
        broadcastAlertFrame(saved, "continue");
        // The card's "ends by itself" time moved (live_activities P1).
        afterCommitOrNow(() -> { if (liveActivities != null) liveActivities.situationChanged(saved.getGroupId()); });
        logger.info("Check-in continued for group {} by {} until {}", groupId, callerEmail, until);
        return until;
    }

    /** Outcome of a nudge: whether a push went out, and whether it was silent. */
    public record NudgeResult(boolean sent, boolean silent) {}

    /**
     * Nudge ONE member — the person sheet's "Nudge" button, as against the
     * admin rollup's ping-everyone-missing. Same cooldown, same event row, so
     * the two paths cannot be used to double the rate.
     *
     * <p>{@code @Transactional} sits on THIS method. Until 2026-09-27 it sat one
     * line higher, on the {@link NudgeResult} record declaration, where Spring
     * never looks — so the cooldown read and the nudge event write ran in no
     * shared transaction at all.</p>
     *
     * @return whether a push went out (false while the nudge is cooling down)
     */
    @Transactional
    public NudgeResult nudgeMember(String groupId, String callerEmail, String subjectEmail) {
        if (callerEmail == null || callerEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to nudge someone");
        }
        if (subjectEmail == null || subjectEmail.isBlank()) return new NudgeResult(false, false);
        Group group = getGroupByPublicId(groupId);
        String me = callerEmail.trim().toLowerCase(Locale.ROOT);
        String them = subjectEmail.trim().toLowerCase(Locale.ROOT);

        if (!MemberActionPolicy.onRoster(group, me)) throw new SecurityException("Only members can nudge");
        // D-2 (owner, 2026-09-29): outside a household only owners and admins
        // nudge. The member-view's viewerCapabilities reads the same policy.
        if (!MemberActionPolicy.canNudge(group, me)) {
            throw new SecurityException("Only organizers can ping people in this group");
        }

        boolean subjectIsMember = safeList(group.getMemberEmails()).stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(them));
        if (!subjectIsMember) throw new SecurityException("That person is not in this household");

        if (!householdEventService.recordNudge(group.getGroupId(), me, them)) return new NudgeResult(false, false);

        String callerName = userInfoRepo.findByUserEmailIgnoreCase(callerEmail)
                .map(UserInfo::getUserFirstName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("Someone in your household");
        // P0-B: the delivery layer decides silence from the RECIPIENT's own
        // situation, so ask it the same question to tell the sender the truth
        // about what their tap did.
        boolean silent = notificationService.shouldSendSilently(them);
        userInfoRepo.findByUserEmailIgnoreCase(them)
                .ifPresent(u -> pushNudge(group, u, callerName));
        // Asking one person IS asking them (ask-to-check-in plan K1): record it
        // like ask-everyone and ping-missing do, so the board can say "asked 5m
        // ago" instead of "not yet asked".
        checkInRequestService.recordAsked(group, java.util.List.of(them), me);
        return new NudgeResult(true, silent);
    }

    private void pushNudge(Group group, UserInfo user, String callerName) {
        String title = group.getGroupName() != null ? group.getGroupName() : "Check in";
        String body = callerName + " is checking who is safe. Tap to share your status.";
        notificationService.deliverPresenceAwareForGroup(
                user.getUserEmail(),
                title,
                body,
                callerName,
                "/images/group-alert-icon.png",
                "check_in_request",
                group.getGroupId(),
                GroupUrlUtil.getGroupTargetUrl(group),
                null,
                user.getFcmtoken(),
                group.getGroupId(),
                Category.CHECK_IN_REQUEST
        );
    }

    private CheckInRollupDto buildCheckInRollup(Group group) {
        boolean active = "Active".equalsIgnoreCase(group.getAlert());
        Instant startedAt = group.getAlertActivatedAt() != null
                ? group.getAlertActivatedAt()
                : group.getUpdatedAt();
        return buildCheckInRollup(group, active, startedAt);
    }

    /**
     * The rollup of a check-in that started at {@code startedAt}, counted the
     * way it was counted while it ran — even after it ended.
     *
     * <p>Once a check-in ends, {@code alertActivatedAt} is cleared and the
     * plain rollup counts every stored status as fresh, so a status from last
     * week would read as an answer. The Live Activity's final "ended" card
     * must show the numbers the check-in actually closed on, so the end path
     * captures the start before the clear and counts against it here.</p>
     */
    public CheckInRollupDto checkInRollupSince(Group group, Instant startedAt) {
        return buildCheckInRollup(group, true, startedAt);
    }

    private CheckInRollupDto buildCheckInRollup(Group group, boolean active, Instant startedAt) {
        List<String> memberEmails = safeList(group.getMemberEmails());
        Map<String, UserInfo> byEmail = memberEmails.isEmpty()
                ? Map.of()
                : userInfoRepo.findByUserEmailIn(memberEmails).stream()
                .filter(u -> u.getUserEmail() != null)
                .collect(Collectors.toMap(
                        u -> u.getUserEmail().trim().toLowerCase(Locale.ROOT),
                        u -> u,
                        (a, b) -> a,
                        LinkedHashMap::new));

        int[] counts = new int[4]; // safe, help, injured, accounted
        List<CheckInRollupDto.Member> members = new ArrayList<>();
        for (String email : memberEmails) {
            String key = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
            UserInfo u = key == null ? null : byEmail.get(key);
            String raw = u == null ? null : u.getUserStatus();
            Instant statusAt = u == null ? null : u.getUserStatusLastUpdated();
            boolean fresh = isFreshCheckIn(statusAt, startedAt, active);
            String status = fresh && raw != null ? raw.trim().toUpperCase(Locale.ROOT) : null;
            boolean accounted = status != null && !status.isBlank();
            if (accounted) {
                counts[3]++;
                switch (status) {
                    case "SAFE" -> counts[0]++;
                    case "HELP" -> counts[1]++;
                    case "INJURED" -> counts[2]++;
                    default -> { }
                }
            }
            members.add(new CheckInRollupDto.Member(
                    key,
                    u == null ? null : u.getUserFirstName(),
                    u == null ? null : u.getUserLastName(),
                    u == null ? null : DtoImages.avatar(u.getProfileImageUrl()),
                    status,
                    statusAt,
                    accounted
            ));
        }

        // Manual members — dependents without accounts — by the member view's
        // own rule (StatusRollups.manualBucket), so /check-in-rollup,
        // ping-missing and the drawer's "N of M" agree. No email: they cannot
        // be nudged, and nudgeMissing skips them.
        if (manualMembers != null
                && HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(group.getGroupType())) {
            List<io.sitprep.sitprepapi.dto.HouseholdAccompanimentDto> acc = accompaniments == null
                    ? List.of() : accompaniments.list(group.getGroupId());
            for (io.sitprep.sitprepapi.dto.HouseholdManualMemberDto m : manualMembers.list(group.getGroupId())) {
                String bucket = StatusRollups.manualBucket(m, acc, active, startedAt);
                boolean accounted = bucket != null;
                if (accounted) {
                    counts[3]++;
                    switch (bucket) {
                        case "HELP" -> counts[1]++;
                        case "INJURED" -> counts[2]++;
                        default -> counts[0]++;
                    }
                }
                members.add(new CheckInRollupDto.Member(
                        null,
                        m.name(),
                        null,
                        DtoImages.avatar(m.photoUrl()),
                        bucket,
                        m.status() == null ? null : m.status().updatedAt(),
                        accounted
                ));
            }
        }

        int total = members.size();
        return new CheckInRollupDto(
                group.getGroupId(),
                group.getGroupName(),
                active,
                startedAt,
                total,
                counts[3],
                counts[0],
                counts[1],
                counts[2],
                Math.max(0, total - counts[3]),
                members,
                active ? group.getAlertExpiresAt() : null
        );
    }

    private static boolean isFreshCheckIn(Instant statusAt, Instant startedAt, boolean active) {
        if (statusAt == null) return false;
        if (!active || startedAt == null) return true;
        return !statusAt.isBefore(startedAt);
    }

    /**
     * Sanitized public preview of a group for non-members. Powers the
     * join-confirmation page (FE: {@code JoinPrivateGroup}) and the
     * discover-surface "view this circle before joining" flow.
     *
     * <p>Does NOT include any email lists. The previous pattern of
     * returning the full {@link Group} entity from
     * {@code GET /api/groups/{groupId}} leaked the entire roster + admin
     * + pending-request emails to any authenticated user with the
     * group id — this method is the safe alternative.</p>
     *
     * @param groupId     the group's public id
     * @param viewerEmail the calling user's email (lowercased), used
     *                    to compute {@code viewerStatus} so the FE can
     *                    pick the right CTA without a second round trip
     * @return preview DTO ready to send to the wire
     */
    public io.sitprep.sitprepapi.dto.GroupPreviewDto getGroupPreview(String groupId, String viewerEmail) {
        Group group = getGroupByPublicId(groupId);
        // A household has no public preview (JN-04, D4). This route is public
        // and served to anyone holding an id; it answered a household's street
        // address and 7-decimal coordinate to an anonymous GET (measured on
        // prod, group 601). Households have their own token-gated
        // /household-invites/{token}/resolve. Same 404 as an unknown id.
        if (isHouseholdGroup(group)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }

        String viewer = viewerEmail == null ? "" : viewerEmail.trim().toLowerCase();

        // Compute viewerStatus in priority order — owner > admin > member
        // > pending > none. A user can be all of those simultaneously
        // (an owner is also typically an admin and a member); the FE
        // only needs the most-privileged label to pick its CTA.
        String status = io.sitprep.sitprepapi.dto.GroupPreviewDto.STATUS_NONE;
        if (group.getOwnerEmail() != null
                && group.getOwnerEmail().equalsIgnoreCase(viewer)) {
            status = io.sitprep.sitprepapi.dto.GroupPreviewDto.STATUS_OWNER;
        } else if (containsIgnoreCase(group.getAdminEmails(), viewer)) {
            status = io.sitprep.sitprepapi.dto.GroupPreviewDto.STATUS_ADMIN;
        } else if (containsIgnoreCase(group.getMemberEmails(), viewer)) {
            status = io.sitprep.sitprepapi.dto.GroupPreviewDto.STATUS_MEMBER;
        } else if (containsIgnoreCase(group.getPendingMemberEmails(), viewer)) {
            status = io.sitprep.sitprepapi.dto.GroupPreviewDto.STATUS_PENDING;
        }

        int adminCount = group.getAdminEmails() == null ? 0 : group.getAdminEmails().size();
        // Accurate count from the member list (the denormalized
        // Group.memberCount drifts and isn't kept in sync on join/leave).
        int memberCount = group.getMemberEmails() == null ? 0 : group.getMemberEmails().size();
        // Whether a check-in is running is a member fact (SH-06 addendum).
        boolean inGroup = io.sitprep.sitprepapi.dto.GroupPreviewDto.STATUS_OWNER.equals(status)
                || io.sitprep.sitprepapi.dto.GroupPreviewDto.STATUS_ADMIN.equals(status)
                || io.sitprep.sitprepapi.dto.GroupPreviewDto.STATUS_MEMBER.equals(status);
        boolean alertActive = inGroup && "Active".equalsIgnoreCase(group.getAlert());
        // Location (D5): never a street address or a coordinate on this public
        // route. A public org shows its city and zip; a private group shows
        // neither (null privacy reads as private, the same rule as join).
        boolean isPublic = "public".equalsIgnoreCase(group.getPrivacy());
        String city = isPublic ? cityOf(group.getAddress()) : null;
        String zip = null;
        if (isPublic) {
            zip = blankToNull(group.getZipCode());
            if (zip == null) zip = zipOf(group.getAddress());
        }

        return new io.sitprep.sitprepapi.dto.GroupPreviewDto(
                group.getGroupId(),
                group.getGroupName(),
                group.getGroupType(),
                group.getDescription(),
                group.getPrivacy(),
                group.getOwnerName(),
                adminCount,
                memberCount,
                city,
                zip,
                group.getCreatedAt(),
                alertActive,
                status
        );
    }

    private static boolean isHouseholdGroup(Group g) {
        return g != null && HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * The city out of a free-form one-line address, or null when it can't be
     * told apart from the street. Addresses come from the geocoder as
     * "street, city, ST 84020" (sometimes with ", USA", a county, or the
     * state and zip in separate parts). Trailing country / state / zip parts
     * are dropped; the city is the last part left, and only when a street part
     * sits before it, so a bare "123 Main St" never comes back as a "city".
     */
    static String cityOf(String address) {
        if (address == null || address.isBlank()) return null;
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String p : address.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        while (!parts.isEmpty() && isRegionPart(parts.get(parts.size() - 1))) {
            parts.remove(parts.size() - 1);
        }
        if (parts.size() < 2) return null;
        String city = parts.get(parts.size() - 1);
        if (city.toLowerCase(Locale.ROOT).endsWith(" county")) {
            if (parts.size() < 3) return null;
            city = parts.get(parts.size() - 2);
        }
        // A part with a digit is a street or a unit, not a city.
        return city.chars().anyMatch(Character::isDigit) ? null : city;
    }

    /**
     * The 5-digit zip from a "street, city, ST 84020" address, or null. The
     * first part is never read: a house number can be five digits too.
     */
    static String zipOf(String address) {
        if (address == null || address.isBlank()) return null;
        String[] parts = address.split(",");
        for (int i = parts.length - 1; i >= 1; i--) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:^|\\s)(\\d{5})(?:-\\d{4})?$").matcher(parts[i].trim());
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static boolean isRegionPart(String part) {
        String p = part.trim();
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.equals("usa") || lower.equals("us") || lower.equals("united states")
                || lower.equals("united states of america")) return true;
        // "UT", "UT 84020", "84020", "84020-1234", "Utah 84020"
        if (p.matches("\\d{5}(-\\d{4})?")) return true;
        if (p.matches("[A-Z]{2}( \\d{5}(-\\d{4})?)?")) return true;
        return p.matches("[A-Za-z ]+ \\d{5}(-\\d{4})?");
    }

    private static boolean containsIgnoreCase(java.util.Collection<String> coll, String needle) {
        if (coll == null || needle == null || needle.isEmpty()) return false;
        for (String s : coll) {
            if (s != null && s.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }


    // ------------------------------------------------------------------
    // Settings (AD-01, B5 2026-10-14)
    // ------------------------------------------------------------------

    /** Column widths of the fields settings may write (V1 baseline; V-business). */
    static final int TEXT_MAX = 255;
    static final int BUSINESS_CATEGORY_MAX = 64;
    static final int WEBSITE_MAX = 512;

    /**
     * {@code PATCH /groups/{id}/settings}: write only the keys the caller sent.
     *
     * <p>The fix for AD-01. The settings Save PUT a whole group read when the
     * page mounted, so it could end a check-in another admin had started,
     * restart one that had ended, and revert roster and link changes made in
     * between. This method can't: the alert, the rosters, the owner, the links,
     * the code and {@code lastUpdatedBy} (which the alert frame reads as "who
     * started the check-in") are not in its vocabulary
     * ({@link io.sitprep.sitprepapi.dto.GroupSettingsRequest#SETTINGS_KEYS}),
     * and the name has its own endpoint.</p>
     *
     * <p>Rules: a household stays Private and stays a household (409); nothing
     * becomes a household here (409); privacy is Public or Private; latitude
     * and longitude travel together; the website is http(s) or a bare domain,
     * never another scheme (it renders as a link on the business card).</p>
     *
     * @throws ResponseStatusException 403 not admin/owner, 400 bad value,
     *         409 household rule
     */
    @Transactional
    public Group updateSettings(String groupId, io.sitprep.sitprepapi.dto.GroupSettingsRequest req, String caller) {
        Group g = getGroupByPublicId(groupId);
        requireAtLeastAdmin(g, caller);
        boolean household = isHouseholdGroup(g);
        if (household && (req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.BUSINESS_CATEGORY)
                || req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.WEBSITE_URL))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A household has no business category or website");
        }

        if (req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.GROUP_TYPE)) {
            String type = req.text(io.sitprep.sitprepapi.dto.GroupSettingsRequest.GROUP_TYPE);
            boolean toHousehold = HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(type);
            if (household) {
                if (!toHousehold) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "A household's type can't change");
                }
            } else {
                if (toHousehold) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "A group can't become a household");
                }
                if (type == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a group type");
                }
                g.setGroupType(bounded(type, TEXT_MAX, "groupType"));
            }
        }

        if (req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.PRIVACY)) {
            String privacy = req.text(io.sitprep.sitprepapi.dto.GroupSettingsRequest.PRIVACY);
            boolean isPublic = "public".equalsIgnoreCase(privacy);
            if (!isPublic && !"private".equalsIgnoreCase(privacy)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "privacy must be Public or Private");
            }
            if (household && isPublic) {
                // D4: households are never listed or joinable by request.
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A household can't be made public. Households are never listed "
                        + "on the community map.");
            }
            g.setPrivacy(isPublic ? "Public" : "Private");
        }

        if (req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.BUSINESS_CATEGORY)) {
            g.setBusinessCategory(bounded(req.text(io.sitprep.sitprepapi.dto.GroupSettingsRequest.BUSINESS_CATEGORY),
                    BUSINESS_CATEGORY_MAX, "businessCategory"));
        }
        if (req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.WEBSITE_URL)) {
            g.setWebsiteUrl(website(req.text(io.sitprep.sitprepapi.dto.GroupSettingsRequest.WEBSITE_URL)));
        }
        applyDetails(g, req);
        g.setUpdatedAt(Instant.now());
        return groupRepo.save(g);
    }

    /**
     * {@code PATCH /groups/{id}/household-details}: the household's name,
     * note and home location in one write. It replaces the fetch-merge-PUT
     * CreateHouseholdGroup made when a member finishes setting up their base
     * household (AD-01's second caller). Households only (409 otherwise);
     * the same admin/owner gate as the PUT it replaces.
     */
    @Transactional
    public Group updateHouseholdDetails(String groupId, io.sitprep.sitprepapi.dto.GroupSettingsRequest req, String caller) {
        Group g = getGroupByPublicId(groupId);
        requireAtLeastAdmin(g, caller);
        if (!isHouseholdGroup(g)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Not a household. Group settings save through /settings.");
        }
        if (req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.GROUP_NAME)) {
            g.setGroupName(normalizeGroupName(req.text(io.sitprep.sitprepapi.dto.GroupSettingsRequest.GROUP_NAME)));
        }
        applyDetails(g, req);
        g.setUpdatedAt(Instant.now());
        return groupRepo.save(g);
    }

    /** Description, address, zip and the coordinate: shared by both PATCHes. */
    private static void applyDetails(Group g, io.sitprep.sitprepapi.dto.GroupSettingsRequest req) {
        if (req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.DESCRIPTION)) {
            g.setDescription(bounded(req.text(io.sitprep.sitprepapi.dto.GroupSettingsRequest.DESCRIPTION),
                    TEXT_MAX, "description"));
        }
        if (req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.ADDRESS)) {
            g.setAddress(bounded(req.text(io.sitprep.sitprepapi.dto.GroupSettingsRequest.ADDRESS), TEXT_MAX, "address"));
        }
        if (req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.ZIP_CODE)) {
            g.setZipCode(bounded(req.text(io.sitprep.sitprepapi.dto.GroupSettingsRequest.ZIP_CODE), TEXT_MAX, "zipCode"));
        }
        boolean hasLat = req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.LATITUDE);
        boolean hasLng = req.has(io.sitprep.sitprepapi.dto.GroupSettingsRequest.LONGITUDE);
        if (hasLat != hasLng) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send latitude and longitude together");
        }
        if (hasLat) {
            Double lat = req.number(io.sitprep.sitprepapi.dto.GroupSettingsRequest.LATITUDE);
            Double lng = req.number(io.sitprep.sitprepapi.dto.GroupSettingsRequest.LONGITUDE);
            if ((lat == null) != (lng == null)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send latitude and longitude together");
            }
            if (lat != null && (lat < -90 || lat > 90 || lng < -180 || lng > 180)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That location is off the map");
            }
            g.setLatitude(lat);
            g.setLongitude(lng);
        }
    }

    private static String bounded(String value, int max, String key) {
        if (value != null && value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    key + " can be up to " + max + " characters");
        }
        return value;
    }

    /** http(s) or a bare domain; any other scheme (javascript:, data:) is a 400. */
    static String website(String raw) {
        String v = bounded(raw, WEBSITE_MAX, "websiteUrl");
        if (v == null) return null;
        if (v.chars().anyMatch(Character::isWhitespace)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That website address isn't valid");
        }
        int colon = v.indexOf(':');
        int slash = v.indexOf('/');
        boolean hasScheme = colon > 0 && (slash < 0 || colon < slash);
        if (hasScheme) {
            String scheme = v.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The website must be an http or https address");
            }
        }
        return v;
    }

    private static <T> List<T> safeList(List<T> list) {
        // Never return null, and strip out null elements so removeIf/equality checks are safe
        List<T> out = new ArrayList<>();
        if (list != null) {
            for (T item : list) {
                if (item != null) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    /**
     * Everything that must happen when a group's alert flips, in ONE place.
     *
     * <p>Five things, and missing any one of them is a silent failure rather
     * than an error: the activation timestamp, the reminder counter (reset on
     * BOTH transitions, so a re-activation gets the full cadence and a manual
     * end cannot leave a pending tick to fire a stale reminder), the member
     * notification, and — for a household — the check-in event log.</p>
     *
     * <p>This was inline in the removed whole-group PUT and reachable only by
     * PUT-ing an entire group. {@link #setAlert} needed the same five, and
     * copying them is how two paths that must agree stop agreeing — so the
     * transition is extracted rather than duplicated. Callers set
     * {@code group.setAlert(...)} themselves; this handles the consequences.</p>
     *
     * <p>No-ops when the state has not actually changed, which is what makes it
     * safe to call unconditionally.</p>
     */
    private void applyAlertTransition(Group group, String previousAlert, String newAlert, String actor) {
        if (Objects.equals(previousAlert, newAlert)) return;

        boolean becameActive = "Active".equalsIgnoreCase(newAlert);
        boolean becameInactive = "Active".equalsIgnoreCase(previousAlert) && !becameActive;

        // Captured BEFORE the clear below: the Live Activity's ended card counts
        // against the start of the check-in that just ended (checkInRollupSince).
        final Instant endedCheckInStart = group.getAlertActivatedAt();

        if (becameActive) {
            Instant start = Instant.now();
            group.setAlertActivatedAt(start);
            group.setAlertExpiresAt(start.plus(java.time.Duration.ofHours(checkInHours)));
            group.setCheckInRemindersFired(0);
        } else if (becameInactive) {
            group.setAlertActivatedAt(null);
            group.setAlertExpiresAt(null);
            group.setCheckInRemindersFired(0);
        }

        if (becameActive) {
            // Opening a check-in window IS asking everyone (RC-2). The window
            // start is set two lines up, so the asks land against the window
            // they belong to rather than against the previous one.
            checkInRequestService.recordAsked(group, group.getMemberEmails(), actor);
            notifyGroupMembers(group);
        }

        if (HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(group.getGroupType())) {
            if (becameActive) {
                householdEventService.recordCheckinStarted(group.getGroupId(), actor);
            } else if (becameInactive) {
                householdEventService.recordCheckinEnded(group.getGroupId(), actor);
            }
        }

        // Live Activities (P1). After commit, and the service hands the work to
        // its own thread: nothing here can fail or slow the write. A new
        // check-in retires tokens left over from an earlier one; an ended one
        // gets its final card.
        if (liveActivities != null && (becameActive || becameInactive)) {
            final String gid = group.getGroupId();
            final LiveActivityPushService la = liveActivities;
            if (becameActive) {
                // The actor, so the "asked to check in" push-to-start skips
                // the person who started it (Live Activities P2).
                afterCommitOrNow(() -> la.situationStarted(gid, actor));
            } else {
                afterCommitOrNow(() -> la.situationEnded(gid, endedCheckInStart,
                        LiveActivityStateBuilder.REASON_MANUAL, actor));
            }
        }
    }

    /** After the commit when there is one; immediately otherwise (registerSynchronization throws without one). */
    private static void afterCommitOrNow(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }

    /**
     * Set ONLY the alert, leaving every other field of the group alone.
     *
     * <p>The frontend had no such endpoint, so toggling an alert meant
     * {@code PUT /groups/{id}} with the whole object — from a snapshot taken
     * when the page mounted. That is not "set the alert", it is "replay every
     * field I last saw", and it silently reverted anything another admin had
     * changed in between. Worse, the frontend's {@code updateGroup} rides the
     * offline outbox, so a toggle made offline replayed a stale WHOLE GROUP
     * whenever connectivity returned.</p>
     *
     * <p>Narrow by construction: it reads the group, changes {@code alert} and
     * whatever {@link #applyAlertTransition} derives from it, and saves. A
     * concurrent rename cannot be lost because the rename is never in the
     * payload.</p>
     */
    @Transactional
    public Group setAlert(String groupId, boolean active, String actor) {
        Group group = getGroupByPublicId(groupId);
        String previousAlert = group.getAlert();
        String newAlert = active ? "Active" : "Inactive";

        group.setAlert(newAlert);
        if (actor != null && !actor.isBlank()) {
            group.setLastUpdatedBy(actor);
        }
        applyAlertTransition(group, previousAlert, newAlert, actor);

        Group saved = groupRepo.save(group);
        broadcastAlertIfChanged(previousAlert, saved);
        return saved;
    }

    /**
     * The after-commit {@link GroupAlertFrame} push, shared by the full update
     * and {@link #setAlert} so the two cannot drift on what listeners receive.
     */
    private void broadcastAlertIfChanged(String previousAlert, Group saved) {
        if (sameAlertState(previousAlert, saved.getAlert())) return;
        broadcastAlertFrame(saved, "manual");
    }

    private void broadcastAlertFrame(Group saved, String reason) {
        GroupAlertFrame frame = new GroupAlertFrame(
                saved.getGroupId(),
                frameAlert(saved.getAlert()),
                saved.getAlertActivatedAt(),
                initiatedBy(saved),
                reason,
                "Active".equalsIgnoreCase(saved.getAlert()) ? saved.getAlertExpiresAt() : null
        );
        // After the commit when there IS one to wait for; immediately otherwise.
        //
        // `registerSynchronization` THROWS when no synchronization is active,
        // so the un-transactional path previously lost the broadcast *and*
        // failed the call. Both public callers are @Transactional, so in
        // production this always takes the first branch — but a helper that
        // explodes depending on how it was reached is a trap for the next
        // caller, and the write has already happened by then either way.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    webSocketMessageSender.sendGroupAlertStatus(saved.getGroupId(), frame);
                }
            });
        } else {
            webSocketMessageSender.sendGroupAlertStatus(saved.getGroupId(), frame);
        }
    }

    private static boolean sameAlertState(String a, String b) {
        return Objects.equals(frameAlert(a), frameAlert(b));
    }

    private static String frameAlert(String alert) {
        return "Active".equalsIgnoreCase(alert) ? "Active" : "Cleared";
    }

    private static String initiatedBy(Group group) {
        if (group.getLastUpdatedBy() != null && !group.getLastUpdatedBy().isBlank()) {
            return group.getLastUpdatedBy();
        }
        if (group.getOwnerEmail() != null && !group.getOwnerEmail().isBlank()) {
            return group.getOwnerEmail();
        }
        return "system";
    }

    /** Trigger full alert fan-out via NotificationService (handles socket + push + logging). */
    private void notifyGroupMembers(Group group) {
        if (group.getAlert() == null || group.getAlert().isEmpty()) {
            logger.warn("❌ Skipping group alert notification: Alert value is null or empty");
            return;
        }
        String initiatedBy = group.getLastUpdatedBy() != null ? group.getLastUpdatedBy() : group.getOwnerEmail();
        logger.info("📣 Alert status change detected for group {}: new status = {}", group.getGroupId(), group.getAlert());
        notificationService.notifyGroupAlertChange(group, group.getAlert(), initiatedBy);
    }

    private void notifyAdminsOfPendingMembers(Group group, Set<String> oldPendingMemberEmails) {
        Set<String> newPendingMemberEmails = new HashSet<>(safeList(group.getPendingMemberEmails()));
        newPendingMemberEmails.removeAll(oldPendingMemberEmails);
        if (newPendingMemberEmails.isEmpty()) return;

        List<UserInfo> admins = userInfoRepo.findByUserEmailIn(
                GroupNotificationRecipients.adminOwnerEmails(group));
        String targetUrl = GroupUrlUtil.getGroupTargetUrl(group);

        // BE-10 (B4): the requesters once, not once per admin per requester;
        // and an email with no account is skipped — it used to throw "User
        // not found" and turn the roster write behind it into a 500.
        List<UserInfo> requesters = knownUsers(newPendingMemberEmails);
        for (UserInfo admin : admins) {
            String token = admin.getFcmtoken();

            for (UserInfo pending : requesters) {

                notificationService.deliverPresenceAware(
                        admin.getUserEmail(),
                        "Hi " + admin.getUserFirstName() + "👋",
                        "A new request from " + pending.getUserFirstName() + " " + pending.getUserLastName() +
                                " is pending for your group, " + group.getGroupName() + ".",
                        "Admin",
                        "/images/admin-icon.png",
                        "pending_member",
                        group.getGroupId(),
                        targetUrl,
                        // additionalData carries the pending requester's email so
                        // the iOS PENDING_MEMBER notification action buttons (Approve /
                        // Decline) can call approveMember(groupId, email) /
                        // rejectPendingMember(groupId, email) directly. See
                        // src/shared/notifications/NotificationActionDispatcher.jsx.
                        pending.getUserEmail(),
                        token,
                        /* actorUserId — the requester, so the admin's row shows who is asking */
                        pending.getId()
                );
            }
        }
    }

    /**
     * The accounts behind these emails, in one query. An email with no account
     * is left out (logged), never thrown on: a notification can't make a
     * roster write fail (BE-10).
     */
    private List<UserInfo> knownUsers(Set<String> emails) {
        if (emails == null || emails.isEmpty()) return List.of();
        List<UserInfo> found = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails));
        if (found.size() < emails.size()) {
            logger.info("Skipping {} roster notification(s) for email(s) with no account",
                    emails.size() - found.size());
        }
        return found;
    }

    private void notifyNewMembers(Group group, Set<String> oldMemberEmails) {
        Set<String> newMemberEmails = new HashSet<>(safeList(group.getMemberEmails()));
        newMemberEmails.removeAll(oldMemberEmails);
        if (newMemberEmails.isEmpty()) return;

        String targetUrl = GroupUrlUtil.getGroupTargetUrl(group);

        for (UserInfo user : knownUsers(newMemberEmails)) {
            String token = user.getFcmtoken();
            if (token == null || token.isEmpty()) continue;

            String msg = "Hi " + user.getUserFirstName() + "👋, you've joined " + group.getGroupName() + ". Welcome!";
            notificationService.sendNotification(
                    "Welcome to " + group.getGroupName() + "!",
                    msg,
                    "User",
                    DtoImages.avatar(user.getProfileImageUrl()),
                    Set.of(token),
                    "new_member",
                    group.getGroupId(),
                    targetUrl,
                    null,
                    user.getUserEmail()
            );
        }
    }

    private void notifyAdminsOfNewMembers(Group group, Set<String> oldMemberEmails) {
        Set<String> newMemberEmails = new HashSet<>(safeList(group.getMemberEmails()));
        newMemberEmails.removeAll(oldMemberEmails);
        if (newMemberEmails.isEmpty()) return;

        List<UserInfo> admins = userInfoRepo.findByUserEmailIn(
                GroupNotificationRecipients.adminOwnerEmails(group));
        String targetUrl = GroupUrlUtil.getGroupTargetUrl(group);

        for (UserInfo newUser : knownUsers(newMemberEmails)) {
            for (UserInfo admin : admins) {
                String token = admin.getFcmtoken();
                if (token == null || token.isEmpty()) continue;

                String msg = newUser.getUserFirstName() + " " + newUser.getUserLastName() +
                        " has joined " + group.getGroupName() + ". Say hello!";
                notificationService.sendNotification(
                        "Hi " + admin.getUserFirstName() + "👋",
                        msg,
                        "Admin",
                        DtoImages.avatar(newUser.getProfileImageUrl()),
                        Set.of(token),
                        "new_member",
                        group.getGroupId(),
                        targetUrl,
                        null,
                        admin.getUserEmail(),
                        /* actorUserId — the person who joined */
                        newUser.getId()
                );
            }
        }
    }

    // ---------------- NEW: Role-aware membership/admin operations ----------------

    /**
     * Self-service join — caller adds themselves to the group.
     *
     * <p>Branches on group privacy:</p>
     * <ul>
     *   <li><b>Public</b> → appended to {@code memberEmails}; the user
     *       also gets the group id in their {@code joinedGroupIDs}
     *       cache so MeContext refresh sees them as a member.</li>
     *   <li><b>Private</b> → appended to {@code pendingMemberEmails};
     *       the existing {@code pending_member} notification fan-out
     *       fires admins' lock-screen Approve / Decline buttons.</li>
     * </ul>
     *
     * <p>Idempotent — calling twice is a no-op (already a member /
     * already pending). Already-admin users short-circuit with no
     * change since they're already members by virtue of being admins.</p>
     *
     * <p>Replaces the previous pattern of FE calling {@code PUT
     * /groups/{id}} with a hand-edited memberEmails array. That path
     * is admin-gated and 403'd for normal users — the join flow has
     * been broken at the auth layer.</p>
     */
    @Transactional
    public Group selfJoin(String groupId, String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Caller email required");
        }
        Group g = getGroupByPublicId(groupId);
        // A household is never joined by request (JN-03): a family is joined
        // through an admin's household invite or claim link, which is the
        // approval. Same 404 as an unknown id.
        if (isHouseholdGroup(g)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
        // Only an explicitly Public group is instant-join. A null or unknown
        // privacy is a request (JN-03): two org rows on prod had none.
        boolean isPrivate = !"public".equalsIgnoreCase(g.getPrivacy());

        // Already a member or admin? No-op.
        if (containsCaseInsensitive(g.getMemberEmails(), email)
                || containsCaseInsensitive(g.getAdminEmails(), email)) {
            return g;
        }

        if (isPrivate) {
            // Already pending? No-op.
            if (containsCaseInsensitive(g.getPendingMemberEmails(), email)) {
                return g;
            }
            // Snapshot the old pending set BEFORE mutation so the diff
            // logic in notifyAdminsOfPendingMembers correctly identifies
            // this caller as "newly pending."
            Set<String> oldPending = g.getPendingMemberEmails() == null
                    ? new HashSet<>()
                    : new HashSet<>(g.getPendingMemberEmails());
            List<String> pending = safeList(g.getPendingMemberEmails());
            pending.add(email);
            g.setPendingMemberEmails(pending);
            g.setUpdatedAt(Instant.now());
            Group saved = groupRepo.save(g);
            // Fire the same notification path admin-triggered edits use
            // so the FCM fan-out (incl. iOS lock-screen Approve/Decline
            // action buttons) goes out to admins identically.
            notifyAdminsOfPendingMembers(saved, oldPending);
            broadcastPendingChangedAfterCommit(saved);
            return saved;
        }

        // Public: instant-join.
        assertSeatAvailable(g, email); // 409 if a paid seat cap is full
        List<String> members = safeList(g.getMemberEmails());
        members.add(email);
        g.setMemberEmails(members);
        g.setMemberCount(members.size());
        g.setUpdatedAt(Instant.now());

        userInfoRepo.findByUserEmail(email).ifPresent(u -> {
            Set<String> updated = addToSet(u.getJoinedGroupIDs(), groupId);
            u.setJoinedGroupIDs(updated);
            userInfoRepo.save(u);
        });

        Group saved = groupRepo.save(g);
        syncHouseholdCountsAfterJoin(saved);
        broadcastMembershipAfterCommit(saved, "ADD", email, GroupRole.MEMBER.wire(), saved.getUpdatedAt());
        return saved;
    }

    /**
     * Join an org group through a link an owner or admin issued (JN-20, D19:
     * the invite is the approval). The caller becomes a member at once, even
     * when the group is private, exactly as if an admin had approved their
     * request: the seat cap is checked (409), a pending request from them is
     * answered, their joined-group ids are synced and the roster hears an ADD.
     *
     * <p>Org groups only: a household token redeems through
     * {@link #joinHouseholdByInvite}, and a household id is the same 404 as
     * an unknown one. The caller (the invite service) decides whether the
     * token still counts as approval, i.e. whether its issuer is still an
     * owner or admin; when not, it calls {@link #selfJoin} instead.</p>
     */
    @Transactional
    public Group joinByInvite(String groupId, String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Caller email required");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        Group g = getGroupByPublicId(groupId);
        if (isHouseholdGroup(g)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
        if (containsCaseInsensitive(g.getMemberEmails(), normalized)
                || containsCaseInsensitive(g.getAdminEmails(), normalized)) {
            syncJoinedGroup(normalized, g.getGroupId());
            return g;
        }
        assertSeatAvailable(g, normalized); // 409 if a paid seat cap is full

        List<String> members = safeList(g.getMemberEmails());
        members.add(normalized);
        g.setMemberEmails(members);
        g.setMemberCount(members.size());
        List<String> pending = safeList(g.getPendingMemberEmails());
        boolean wasPending = pending.removeIf(e -> e.trim().equalsIgnoreCase(normalized));
        if (wasPending) {
            g.setPendingMemberEmails(pending);
        }
        g.setUpdatedAt(Instant.now());
        syncJoinedGroup(normalized, g.getGroupId());

        Group saved = groupRepo.save(g);
        broadcastMembershipAfterCommit(saved, "ADD", normalized, GroupRole.MEMBER.wire(), saved.getUpdatedAt());
        if (wasPending) broadcastPendingChangedAfterCommit(saved); // their request was answered
        return saved;
    }

    /**
     * A requester withdraws their own join request (JN-21, D3). True when a
     * request was there to withdraw. The roster hears {@link #PENDING_CHANGED}.
     */
    @Transactional
    public boolean withdrawPendingRequest(String groupId, String email) {
        if (email == null || email.isBlank()) return false;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        Group g = getGroupByPublicId(groupId);
        List<String> pending = safeList(g.getPendingMemberEmails());
        if (!pending.removeIf(e -> e.trim().equalsIgnoreCase(normalized))) return false;
        g.setPendingMemberEmails(pending);
        g.setUpdatedAt(Instant.now());
        Group saved = groupRepo.save(g);
        broadcastPendingChangedAfterCommit(saved);
        return true;
    }

    /**
     * Trusted household invite redemption. Unlike generic private-group
     * self-join, an admin-issued household token is the approval signal, so the
     * recipient becomes a household member immediately.
     */
    @Transactional
    public Group joinHouseholdByInvite(String householdId, String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Caller email required");
        }
        Group g = getGroupByPublicId(householdId);
        if (!HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite is not for a household");
        }

        if (containsCaseInsensitive(g.getMemberEmails(), email)
                || containsCaseInsensitive(g.getAdminEmails(), email)) {
            ensureUserHouseholdLink(email, householdId);
            return g;
        }

        Set<String> oldMembers = new HashSet<>(safeList(g.getMemberEmails()));
        List<String> members = safeList(g.getMemberEmails());
        members.add(email.trim().toLowerCase(Locale.ROOT));
        g.setMemberEmails(members);
        g.setMemberCount(members.size());
        g.setUpdatedAt(Instant.now());

        ensureUserHouseholdLink(email, householdId);

        Group saved = groupRepo.save(g);
        syncHouseholdCountsAfterJoin(saved);
        notifyNewMembers(saved, oldMembers);
        notifyAdminsOfNewMembers(saved, oldMembers);
        broadcastMembershipAfterCommit(saved, "ADD", email, GroupRole.MEMBER.wire(), saved.getUpdatedAt());
        return saved;
    }

    /**
     * Household join through a CLAIM link (household roster EXEC-B). Same
     * trusted add as {@link #joinHouseholdByInvite} — the admin-issued token is
     * the approval — but deliberately WITHOUT the count sync, the base-household
     * write and the join notifications: the claim moves an already-counted slot
     * from a manual row to this account (counts unchanged), decides the base
     * household by its own rule, and sends its own "claimed their spot" notice.
     * Idempotent. Returns true when the caller was newly added.
     */
    @Transactional
    public boolean joinHouseholdByClaim(String householdId, String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Caller email required");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        Group g = getGroupByPublicId(householdId);
        if (!HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a household");
        }
        syncJoinedGroup(normalized, householdId);
        if (containsCaseInsensitive(g.getMemberEmails(), normalized)) return false;

        List<String> members = safeList(g.getMemberEmails());
        members.add(normalized);
        g.setMemberEmails(members);
        g.setMemberCount(members.size());
        // Pending request from the same person is answered by the claim.
        List<String> pending = safeList(g.getPendingMemberEmails());
        if (pending.removeIf(e -> e != null && e.trim().equalsIgnoreCase(normalized))) {
            g.setPendingMemberEmails(pending);
        }
        g.setUpdatedAt(Instant.now());
        Group saved = groupRepo.save(g);
        broadcastMembershipAfterCommit(saved, "ADD", normalized,
                GroupRole.fromGroup(saved, normalized).wire(), saved.getUpdatedAt());
        return true;
    }

    private void ensureUserHouseholdLink(String email, String householdId) {
        userInfoRepo.findByUserEmailIgnoreCase(email).ifPresent(u -> {
            Set<String> updated = addToSet(u.getJoinedGroupIDs(), householdId);
            u.setJoinedGroupIDs(updated);
            if (u.getBaseHouseholdId() == null || u.getBaseHouseholdId().isBlank()) {
                u.setBaseHouseholdId(householdId);
            }
            userInfoRepo.save(u);
        });
    }

    private static boolean containsCaseInsensitive(java.util.Collection<String> coll, String needle) {
        if (coll == null || needle == null || needle.isEmpty()) return false;
        for (String s : coll) {
            if (s != null && s.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }

    /**
     * Enforce the paid-tier seat cap before a NEW member is added. Seats are
     * written by the Stripe webhook from the subscription line-item quantity
     * (AGENCY / BUSINESS). Null / &le;0 = unlimited (FREE / legacy rows).
     * Re-adding an existing member consumes no new seat. Over-capacity throws
     * 409 CONFLICT so the caller can surface "seats full — add seats in billing".
     */
    private void assertSeatAvailable(Group g, String newMemberEmail) {
        Integer max = g.getMaxSeats();
        if (max == null || max <= 0) return; // unlimited / unseated tier
        List<String> members = g.getMemberEmails();
        boolean alreadyMember = members != null && members.stream()
                .anyMatch(e -> e != null && e.trim().equalsIgnoreCase(newMemberEmail));
        if (alreadyMember) return; // already occupies a seat — re-add is a no-op
        int current = members == null ? 0 : members.size();
        if (current >= max) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This plan is at its seat limit (" + max + "). Add seats in billing to invite more people.");
        }
    }

    @Transactional
    public Group approveMember(String groupId, String email) {
        approveMemberAction(groupId, email);
        return getGroupByPublicId(groupId);
    }

    @Transactional
    public GroupMembershipActionResultDto approveMemberAction(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        String normalizedEmail = normalizeActionEmail(email);

        List<String> members = safeList(g.getMemberEmails());
        List<String> pending = safeList(g.getPendingMemberEmails());

        // null-safe removal of pending email
        boolean wasPending = pending.removeIf(e -> e != null && e.trim().equalsIgnoreCase(normalizedEmail));

        // null-safe membership check
        boolean alreadyMember = members.stream()
                .anyMatch(e -> e != null && e.trim().equalsIgnoreCase(normalizedEmail));
        if (!wasPending && alreadyMember) {
            syncJoinedGroup(normalizedEmail, groupId);
            return membershipResult("APPROVE", "ALREADY_MEMBER", g, normalizedEmail);
        }
        if (!wasPending) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No pending request for email");
        }
        if (!alreadyMember) {
            assertSeatAvailable(g, normalizedEmail); // 409 if a paid seat cap is full
            members.add(normalizedEmail);
        }

        g.setMemberEmails(members);
        g.setPendingMemberEmails(pending);
        g.setMemberCount(members.size());
        g.setUpdatedAt(Instant.now());

        syncJoinedGroup(normalizedEmail, groupId);

        Group saved = groupRepo.save(g);
        if (!alreadyMember) {
            syncHouseholdCountsAfterJoin(saved);
            broadcastMembershipAfterCommit(
                    saved,
                    "ADD",
                    normalizedEmail,
                    GroupRole.fromGroup(saved, normalizedEmail).wire(),
                    saved.getUpdatedAt());
            notifyApprovedMemberAfterCommit(saved, normalizedEmail);
        }
        return membershipResult(
                "APPROVE",
                alreadyMember ? "ALREADY_MEMBER" : "APPROVED",
                saved,
                normalizedEmail
        );
    }

    @Transactional
    public Group rejectPendingMember(String groupId, String email) {
        rejectPendingMemberAction(groupId, email);
        return getGroupByPublicId(groupId);
    }

    @Transactional
    public GroupMembershipActionResultDto rejectPendingMemberAction(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        String normalizedEmail = normalizeActionEmail(email);

        List<String> pending = safeList(g.getPendingMemberEmails());
        // null-safe removal
        boolean wasPending = pending.removeIf(e -> e != null && e.trim().equalsIgnoreCase(normalizedEmail));

        Group saved = g;
        if (wasPending) {
            g.setPendingMemberEmails(pending);
            g.setUpdatedAt(Instant.now());
            saved = groupRepo.save(g);
        }
        return membershipResult(
                "REJECT",
                wasPending ? "REJECTED" : "NOT_PENDING",
                saved,
                normalizedEmail
        );
    }

    private static String normalizeActionEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email required");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid email required");
        }
        return normalized;
    }

    private static GroupMembershipActionResultDto membershipResult(
            String action,
            String status,
            Group group,
            String email
    ) {
        int memberCount = group.getMemberEmails() == null ? 0 : group.getMemberEmails().size();
        int pendingCount = group.getPendingMemberEmails() == null ? 0 : group.getPendingMemberEmails().size();
        return new GroupMembershipActionResultDto(
                true,
                action,
                status,
                group.getGroupId(),
                email,
                GroupRole.fromGroup(group, email).wire(),
                memberCount,
                pendingCount,
                group.getUpdatedAt()
        );
    }

    private void syncJoinedGroup(String email, String groupId) {
        if (email == null || email.isBlank() || groupId == null || groupId.isBlank()) return;
        userInfoRepo.findByUserEmailIgnoreCase(email.trim()).ifPresent(u -> {
            Set<String> updated = addToSet(u.getJoinedGroupIDs(), groupId);
            u.setJoinedGroupIDs(updated);
            userInfoRepo.save(u);
        });
    }

    private void notifyApprovedMemberAfterCommit(Group group, String email) {
        if (group == null || email == null || email.isBlank()) return;
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String groupName = group.getGroupName() == null || group.getGroupName().isBlank()
                ? "your group"
                : group.getGroupName();
        String targetUrl = GroupUrlUtil.getGroupTargetUrl(group);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                UserInfo recipient = userInfoRepo.findByUserEmailIgnoreCase(normalizedEmail).orElse(null);
                String firstName = recipient == null ? "" : nullToEmpty(recipient.getUserFirstName()).trim();
                String greeting = firstName.isBlank() ? "You're in" : firstName + ", you're in";
                notificationService.deliverPresenceAware(
                        normalizedEmail,
                        "You're approved",
                        greeting + " " + groupName + ". You can now open the group and join the conversation.",
                        groupName,
                        null,
                        "new_member",
                        group.getGroupId(),
                        targetUrl,
                        null,
                        recipient == null ? null : recipient.getFcmtoken()
                );
            }
        });
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Remove an account from a group.
     *
     * <p>For a household (EXEC-B, 2026-10-07): the removed person's band row is
     * dropped — their slot stays counted and becomes an unnamed placeholder
     * until an admin lowers the count — and when this was their BASE
     * household, the base is cleared and re-provisioned exactly like the leave
     * path: another household they belong to, else a new personal one (the
     * "every user has a base household" guarantee {@link HouseholdProvisioningService}
     * owns). Left pointing at the old id, {@code /api/me} would keep anchoring
     * them to a household they are no longer in.</p>
     */
    /**
     * {@code POST /members/remove}: an admin removes someone (BE-08).
     *
     * <p>The owner can't be removed (409: transfer first). Removing another
     * admin takes the owner; an admin may still remove themselves. Without
     * that, any admin could strip the others and hold the group alone, and the
     * owner could be taken off the roster while {@code ownerEmail} kept them
     * OWNER.</p>
     */
    @Transactional
    public Group removeMember(String groupId, String email, String caller) {
        Group g = getGroupByPublicId(groupId);
        requireAtLeastAdmin(g, caller);
        String target = normalizeActionEmail(email);
        if (g.getOwnerEmail() != null && g.getOwnerEmail().trim().equalsIgnoreCase(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The owner can't be removed. Transfer ownership first.");
        }
        requireOwnerToTouchAnotherAdmin(g, target, caller);
        return removeMember(groupId, target);
    }

    /**
     * Remove an account from the roster. No caller check: the callers are
     * {@link #removeMember(String, String, String)} and the self-leave path,
     * which gate first. Refuses the owner whatever the path (409).
     */
    @Transactional
    public Group removeMember(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        final String normalized = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        if (normalized != null && g.getOwnerEmail() != null
                && g.getOwnerEmail().trim().equalsIgnoreCase(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The owner can't be removed. Transfer ownership first.");
        }

        List<String> members = safeList(g.getMemberEmails());
        List<String> admins = safeList(g.getAdminEmails());
        boolean wasMember = containsCaseInsensitive(members, normalized);
        String previousRole = GroupRole.fromGroup(g, normalized).wire();

        // null-safe removals
        members.removeIf(e -> e != null && e.trim().equalsIgnoreCase(normalized));
        admins.removeIf(e -> e != null && e.trim().equalsIgnoreCase(normalized));

        g.setMemberEmails(members);
        g.setAdminEmails(admins);
        g.setMemberCount(members.size());
        g.setUpdatedAt(Instant.now());

        Group saved = groupRepo.saveAndFlush(g);
        boolean isHousehold = HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(saved.getGroupType());
        if (isHousehold && householdMemberBandRepo != null && normalized != null) {
            householdMemberBandRepo.deleteMembership(groupId, normalized);
        }
        // Their support needs, the plan for them, and any plan naming them as a
        // helper leave with them (leave() comes through here too).
        if (isHousehold && supportService != null && normalized != null && wasMember) {
            supportService.cascadeSubjectRemoval(groupId, "user", normalized);
            supportService.cascadeHelperRemoval(groupId, normalized);
        }

        if (normalized != null) {
            userInfoRepo.findByUserEmailIgnoreCase(normalized).ifPresent(u -> {
                Set<String> j = removeFromSet(u.getJoinedGroupIDs(), groupId);
                Set<String> m = removeFromSet(u.getManagedGroupIDs(), groupId);
                u.setJoinedGroupIDs(j);
                u.setManagedGroupIDs(m);
                boolean reanchor = groupId.equals(u.getBaseHouseholdId());
                if (reanchor) u.setBaseHouseholdId(null);
                userInfoRepo.save(u);
                if (reanchor && householdProvisioning != null) {
                    householdProvisioning.ensureBaseHousehold(u);
                }
            });
        }

        if (wasMember) {
            broadcastMembershipAfterCommit(saved, "REMOVE", normalized, previousRole, saved.getUpdatedAt());
        }
        // BE-13: SUBSCRIBE was the only check, so a removed member's open page
        // kept receiving the group's frames until it reconnected. Registered
        // after the REMOVE broadcast, so their client still hears that it was
        // removed, then hears nothing more.
        if (topicEvictor != null && normalized != null) {
            final io.sitprep.sitprepapi.websocket.GroupTopicEvictor evictor = topicEvictor;
            afterCommitOrNow(() -> evictor.evict(groupId, normalized));
        }
        return saved;
    }

    /**
     * {@code POST /admins/add}: promote someone who is ALREADY a member (BE-02,
     * D2). Anyone else is a 409: membership is the person's own act, so "add
     * someone new" is an invite, never a roster write. Idempotent for an
     * existing admin.
     */
    @Transactional
    public Group promoteMemberToAdmin(String groupId, String email, String caller) {
        Group g = getGroupByPublicId(groupId);
        requireAtLeastAdmin(g, caller);
        String target = normalizeActionEmail(email);
        if (!containsCaseInsensitive(g.getMemberEmails(), target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only members can be made admins. Invite them to the group first.");
        }
        return addAdmin(groupId, target);
    }

    /**
     * Grant admin, adding the person as a member if they aren't one.
     *
     * <p><b>Platform console only</b> ({@code AdminAgencyRosterResource}), where
     * a SitPrep admin provisions an agency's staff white-glove. A group admin
     * goes through {@link #promoteMemberToAdmin}, which refuses non-members.</p>
     */
    @Transactional
    public Group addAdmin(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        final String target = normalizeActionEmail(email);

        List<String> admins = safeList(g.getAdminEmails());
        boolean alreadyAdmin = containsCaseInsensitive(admins, target);
        if (!alreadyAdmin) {
            admins.add(target);
        }

        List<String> members = safeList(g.getMemberEmails());
        boolean alreadyMember = containsCaseInsensitive(members, target);
        if (!alreadyMember) {
            assertSeatAvailable(g, target);
            members.add(target); // admin must be a member too
        }

        g.setAdminEmails(admins);
        g.setMemberEmails(members);
        g.setMemberCount(members.size());
        g.setUpdatedAt(Instant.now());

        userInfoRepo.findByUserEmailIgnoreCase(target).ifPresent(u -> {
            Set<String> m = addToSet(u.getManagedGroupIDs(), groupId);
            Set<String> j = addToSet(u.getJoinedGroupIDs(), groupId);
            u.setManagedGroupIDs(m);
            u.setJoinedGroupIDs(j);
            userInfoRepo.save(u);
        });

        Group saved = groupRepo.save(g);
        if (!alreadyMember) {
            syncHouseholdCountsAfterJoin(saved);
            broadcastMembershipAfterCommit(
                    saved,
                    "ADD",
                    target,
                    GroupRole.fromGroup(saved, target).wire(),
                    saved.getUpdatedAt());
        }
        return saved;
    }

    /**
     * Set (or clear) the group's custom logo URL — Phase 4 of
     * SitPrep FE docs/guidelines/product/BUSINESS_MODEL.md ("co-branded page"). A null / blank value
     * reverts the group to its default type emblem. Caller
     * authorization (admin/owner) is enforced at the resource layer.
     */
    @Transactional
    public Group setLogo(String groupId, String logoImageUrl) {
        Group g = getGroupByPublicId(groupId);
        String url = (logoImageUrl == null || logoImageUrl.isBlank())
                ? null : logoImageUrl.trim();
        g.setLogoImageUrl(url);
        g.setUpdatedAt(Instant.now());
        return groupRepo.save(g);
    }

    /**
     * Set or clear the circle's cover image. Null / blank clears it, which
     * reverts the hero to the group type's default gradient.
     *
     * <p>Narrow like {@link #setLogo}, and for the same reason the alert got
     * its own endpoint: the alternative is a whole-object PUT that replays
     * every other field from whatever the client last saw.</p>
     */
    @Transactional
    public Group setCover(String groupId, String coverImageUrl) {
        Group g = getGroupByPublicId(groupId);
        String url = (coverImageUrl == null || coverImageUrl.isBlank())
                ? null : coverImageUrl.trim();
        g.setCoverImageUrl(url);
        g.setUpdatedAt(Instant.now());
        return groupRepo.save(g);
    }

    /**
     * Longest group name the {@code group_name} column holds (VARCHAR(255),
     * V1 baseline). Past it the insert fails as a 500; checked here so it is
     * a 400 with a reason instead.
     */
    public static final int GROUP_NAME_MAX = 255;

    /**
     * Rename a group, and nothing else.
     *
     * <p>Narrow for the same reason as {@link #setAlert} and {@link #setCover}:
     * the rename used to be a fetch of the whole group followed by a PUT of the
     * whole group, so it replayed every field the client had read — rosters
     * included — over anything another admin changed in between, and rode the
     * offline outbox while doing it.</p>
     *
     * <p>Gate: admin or owner, the same gate as the whole-group PUT it replaces
     * ({@code GroupResource#requireAdminOf}). Name rule: the create flows'
     * rule (trimmed, not blank) plus the column width.</p>
     *
     * <p>No broadcast: the whole-group PUT broadcasts only an alert transition
     * and resyncs household counts after a roster change, and a rename causes
     * neither. {@code lastUpdatedBy} is deliberately left alone — the alert
     * frame reads it as "who started the check-in".</p>
     *
     * @throws ResponseStatusException 403 when the caller isn't admin / owner,
     *         400 when the name is blank or too long
     */
    @Transactional
    public Group renameGroup(String groupId, String rawName, String caller) {
        Group g = getGroupByPublicId(groupId);
        requireAtLeastAdmin(g, caller);
        String name = normalizeGroupName(rawName);
        if (name.equals(g.getGroupName())) return g; // idempotent: nothing to write
        g.setGroupName(name);
        g.setUpdatedAt(Instant.now());
        return groupRepo.save(g);
    }

    /** Trim; reject blank and over-width names with a 400. */
    static String normalizeGroupName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Group name is required");
        }
        if (name.length() > GROUP_NAME_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Group name is too long (max " + GROUP_NAME_MAX + " characters)");
        }
        return name;
    }

    /**
     * Link {@code childGroupId} under {@code parentGroupId}: the parent's
     * {@code subGroupIDs} gains the child and the child's
     * {@code parentGroupIDs} gains the parent, in ONE transaction.
     *
     * <p>Replaces two read-modify-write PUTs of two whole groups, queued
     * through the offline outbox, which could replay stale rosters much later
     * and could land one side of the link without the other
     * ({@code GroupOwnerContactService} only honours a link both sides agree
     * on). Here both sides commit or neither does.</p>
     *
     * <p>Gate: admin or owner of BOTH groups — exactly what the two whole-group
     * PUTs required between them. Idempotent: linking an existing link writes
     * nothing and returns the same lists.</p>
     *
     * <p>No loops (owner ruling 2026-10-13): once A is under B, B can't be put
     * under A, nor under anything below A. A link that would close a loop is
     * refused with 409.</p>
     *
     * @throws ResponseStatusException 400 for a self-link, 403 when the caller
     *         isn't admin / owner of either group, 409 when the link would make
     *         a group its own ancestor
     */
    @Transactional
    public io.sitprep.sitprepapi.dto.SubGroupLinkDto linkSubGroup(String parentGroupId, String childGroupId, String caller) {
        return setSubGroupLink(parentGroupId, childGroupId, caller, true);
    }

    /**
     * Undo {@link #linkSubGroup}: both sides lose each other, in one
     * transaction. Same gate. Idempotent, and it also clears a half-link left
     * by the old two-PUT path (one side listing the other, not both).
     */
    @Transactional
    public io.sitprep.sitprepapi.dto.SubGroupLinkDto unlinkSubGroup(String parentGroupId, String childGroupId, String caller) {
        return setSubGroupLink(parentGroupId, childGroupId, caller, false);
    }

    private io.sitprep.sitprepapi.dto.SubGroupLinkDto setSubGroupLink(
            String parentGroupId, String childGroupId, String caller, boolean link) {
        if (parentGroupId == null || childGroupId == null || parentGroupId.equals(childGroupId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A group can't be its own subgroup");
        }
        Group parent = getGroupByPublicId(parentGroupId);
        Group child = getGroupByPublicId(childGroupId);
        requireAtLeastAdmin(parent, caller);
        requireAtLeastAdmin(child, caller);

        List<String> subs = safeList(parent.getSubGroupIDs());
        List<String> parents = safeList(child.getParentGroupIDs());
        final String childId = child.getGroupId();
        final String parentId = parent.getGroupId();

        // A NEW link must not close a loop. An existing link is left alone, so
        // re-linking stays an idempotent no-op even for a legacy row.
        if (link && !(subs.contains(childId) && parents.contains(parentId))
                && wouldCreateCycle(parent, child)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That would put a group under one of its own subgroups. "
                            + child.getGroupName() + " already contains " + parent.getGroupName() + ".");
        }

        boolean parentChanged = link ? addIfAbsent(subs, childId) : subs.removeIf(childId::equals);
        boolean childChanged = link ? addIfAbsent(parents, parentId) : parents.removeIf(parentId::equals);

        Instant now = Instant.now();
        if (parentChanged) {
            parent.setSubGroupIDs(subs);
            parent.setUpdatedAt(now);
            groupRepo.save(parent);
        }
        if (childChanged) {
            child.setParentGroupIDs(parents);
            child.setUpdatedAt(now);
            groupRepo.save(child);
        }
        return new io.sitprep.sitprepapi.dto.SubGroupLinkDto(
                parentId, childId, link, List.copyOf(subs), List.copyOf(parents));
    }

    /** How far the loop check walks before giving up (a real hierarchy is shallow). */
    private static final int SUBGROUP_WALK_LIMIT = 500;

    /**
     * Would putting {@code child} under {@code parent} make a loop? True when
     * {@code child} is already {@code parent} or one of its ancestors.
     *
     * <p>Walks BOTH directions, because the old two-PUT path could leave
     * half-links (one side lists the other, the other side doesn't): up from
     * {@code parent} through {@code parentGroupIDs}, and down from
     * {@code child} through {@code subGroupIDs}. Ids that no longer resolve
     * are skipped. Bounded by {@link #SUBGROUP_WALK_LIMIT} visits, so a
     * corrupt graph can't hang the request; hitting the bound refuses the link
     * rather than risk a loop.</p>
     */
    private boolean wouldCreateCycle(Group parent, Group child) {
        final String target = child.getGroupId();
        final String start = parent.getGroupId();
        if (reaches(parent, target, true)) return true;   // child is above parent
        return reaches(child, start, false);              // parent is below child
    }

    private boolean reaches(Group from, String targetId, boolean upward) {
        java.util.ArrayDeque<Group> queue = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        queue.add(from);
        seen.add(from.getGroupId());
        while (!queue.isEmpty()) {
            if (seen.size() > SUBGROUP_WALK_LIMIT) return true;
            Group g = queue.poll();
            List<String> next = safeList(upward ? g.getParentGroupIDs() : g.getSubGroupIDs());
            for (String id : next) {
                if (id == null) continue;
                if (id.equals(targetId)) return true;
                if (seen.add(id)) groupRepo.findByGroupId(id).ifPresent(queue::add);
            }
        }
        return false;
    }

    private static boolean addIfAbsent(List<String> list, String id) {
        if (list.contains(id)) return false;
        list.add(id);
        return true;
    }

    /** 403 unless {@code caller} is admin or owner of {@code g}. */
    private static void requireAtLeastAdmin(Group g, String caller) {
        if (caller == null || !GroupRole.fromGroup(g, caller).isAtLeastAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Group admin or owner role required");
        }
    }

    /**
     * {@code POST /admins/remove}: an admin demotes an admin (BE-08). Demoting
     * ANOTHER admin takes the owner (403), the same rule as removing one, or an
     * admin could demote and then remove. Self-demotion is allowed.
     */
    @Transactional
    public Group removeAdmin(String groupId, String email, String caller) {
        Group g = getGroupByPublicId(groupId);
        requireAtLeastAdmin(g, caller);
        String target = normalizeActionEmail(email);
        requireOwnerToTouchAnotherAdmin(g, target, caller);
        return removeAdmin(groupId, target);
    }

    /**
     * Drop the admin role. Refuses the owner with a {@link SecurityException},
     * which {@code GlobalExceptionHandler} answers 403. No caller check: the
     * group path is {@link #removeAdmin(String, String, String)}; the platform
     * console gates itself.
     */
    @Transactional
    public Group removeAdmin(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        final String target = normalizeActionEmail(email);

        if (g.getOwnerEmail() != null && g.getOwnerEmail().trim().equalsIgnoreCase(target)) {
            throw new SecurityException("Cannot remove owner admin role. Transfer ownership first.");
        }

        List<String> admins = safeList(g.getAdminEmails());
        admins.removeIf(e -> e != null && e.trim().equalsIgnoreCase(target));
        g.setAdminEmails(admins);
        g.setUpdatedAt(Instant.now());

        userInfoRepo.findByUserEmailIgnoreCase(target).ifPresent(u -> {
            Set<String> m = removeFromSet(u.getManagedGroupIDs(), groupId);
            u.setManagedGroupIDs(m);
            userInfoRepo.save(u);
        });

        return groupRepo.save(g);
    }

    /**
     * {@code POST /owner/transfer}: the owner hands the group to an EXISTING
     * member (BE-02, D2), who is told. Anyone else is a 409. Transferring to
     * yourself is a no-op.
     */
    @Transactional
    public Group transferOwnershipToMember(String groupId, String newOwnerEmail, String caller) {
        Group g = getGroupByPublicId(groupId);
        if (caller == null || GroupRole.fromGroup(g, caller) != GroupRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Group owner role required");
        }
        String target = normalizeActionEmail(newOwnerEmail);
        if (!containsCaseInsensitive(g.getMemberEmails(), target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ownership can only go to a member of the group.");
        }
        if (g.getOwnerEmail() != null && g.getOwnerEmail().trim().equalsIgnoreCase(target)) {
            return g;
        }
        String previousOwner = g.getOwnerEmail();
        Group saved = transferOwner(groupId, target);
        // MB-03: the old owner stays on as an admin (they were the owner, so they
        // were never forced into adminEmails before).
        if (previousOwner != null && !previousOwner.isBlank()) {
            List<String> admins = safeList(saved.getAdminEmails());
            if (!containsCaseInsensitive(admins, previousOwner)) {
                admins.add(previousOwner.trim());
                saved.setAdminEmails(admins);
                saved = groupRepo.save(saved);
            }
        }
        notifyNewOwnerAfterCommit(saved, target, caller);
        return saved;
    }

    /**
     * Set a single owner and make sure they are admin and member.
     *
     * <p><b>Platform console only</b> ({@code AdminAgencyRosterResource}), which
     * may hand an agency to someone not yet on it. A group owner goes through
     * {@link #transferOwnershipToMember}.</p>
     */
    @Transactional
    public Group transferOwner(String groupId, String newOwnerEmail) {
        Group g = getGroupByPublicId(groupId);
        final String target = normalizeActionEmail(newOwnerEmail);

        g.setOwnerEmail(target);
        userInfoRepo.findByUserEmailIgnoreCase(target).ifPresent(u -> {
            String full = (nullToEmpty(u.getUserFirstName()) + " " + nullToEmpty(u.getUserLastName())).trim();
            if (!full.isEmpty()) g.setOwnerName(full);
        });

        List<String> admins = safeList(g.getAdminEmails());
        if (!containsCaseInsensitive(admins, target)) {
            admins.add(target);
        }
        g.setAdminEmails(admins);

        List<String> members = safeList(g.getMemberEmails());
        boolean alreadyMember = containsCaseInsensitive(members, target);
        if (!alreadyMember) {
            members.add(target);
        }
        g.setMemberEmails(members);
        g.setMemberCount(members.size());

        g.setUpdatedAt(Instant.now());

        userInfoRepo.findByUserEmailIgnoreCase(target).ifPresent(u -> {
            Set<String> m = addToSet(u.getManagedGroupIDs(), groupId);
            Set<String> j = addToSet(u.getJoinedGroupIDs(), groupId);
            u.setManagedGroupIDs(m);
            u.setJoinedGroupIDs(j);
            userInfoRepo.save(u);
        });

        Group saved = groupRepo.save(g);
        if (!alreadyMember) {
            syncHouseholdCountsAfterJoin(saved);
            broadcastMembershipAfterCommit(
                    saved,
                    "ADD",
                    target,
                    GroupRole.fromGroup(saved, target).wire(),
                    saved.getUpdatedAt());
        }
        return saved;
    }

    /**
     * Tell the new owner, as {@code role_changed}: a Lane A push (it used to
     * ride {@code new_member}, which is inbox-only, so the phone never heard).
     */
    private void notifyNewOwnerAfterCommit(Group group, String newOwner, String previousOwner) {
        if (group == null || newOwner == null || newOwner.isBlank() || notificationService == null) return;
        final String groupName = group.getGroupName() == null || group.getGroupName().isBlank()
                ? "your group" : group.getGroupName();
        final String targetUrl = GroupUrlUtil.getGroupTargetUrl(group);
        afterCommitOrNow(() -> {
            UserInfo recipient = userInfoRepo.findByUserEmailIgnoreCase(newOwner).orElse(null);
            UserInfo from = previousOwner == null ? null
                    : userInfoRepo.findByUserEmailIgnoreCase(previousOwner.trim()).orElse(null);
            String fromName = from == null ? "" : nullToEmpty(from.getUserFirstName()).trim();
            String body = (fromName.isEmpty() ? "You're" : fromName + " made you")
                    + (fromName.isEmpty() ? " now the owner of " : " the owner of ") + groupName + ".";
            notificationService.deliverPresenceAware(
                    newOwner,
                    "You're the owner now",
                    body,
                    groupName,
                    null,
                    "role_changed",
                    group.getGroupId(),
                    targetUrl,
                    null,
                    recipient == null ? null : recipient.getFcmtoken()
            );
        });
    }

    /** 403 unless the caller owns the group, when the target is another admin. */
    private static void requireOwnerToTouchAnotherAdmin(Group g, String target, String caller) {
        boolean targetIsAdmin = GroupRole.fromGroup(g, target).isAtLeastAdmin();
        boolean self = caller != null && caller.trim().equalsIgnoreCase(target);
        if (targetIsAdmin && !self && GroupRole.fromGroup(g, caller) != GroupRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the owner can remove another admin.");
        }
    }

    private void broadcastMembershipAfterCommit(Group group, String action, String email,
                                                String role, Instant updatedAt) {
        if (group == null || group.getGroupId() == null || group.getGroupId().isBlank()) return;
        if (email == null || email.isBlank()) return;

        GroupMembershipFrame frame = new GroupMembershipFrame(
                action,
                email.trim().toLowerCase(Locale.ROOT),
                role,
                updatedAt != null ? updatedAt : Instant.now()
        );
        // After the commit when there is one; immediately otherwise
        // (registerSynchronization throws without one).
        afterCommitOrNow(() -> webSocketMessageSender.sendGroupMembership(group.getGroupId(), frame));
    }

    /**
     * Membership-frame action meaning "the join requests changed; re-read
     * them". It carries no email and no role on purpose: the members topic
     * reaches plain members, who never see who asked to join (D3, D6), so the
     * frame says only that admins' pending list moved (a request arrived or
     * was withdrawn). Clients that don't know the action ignore it
     * ({@code useGroupMembershipStream} handles ADD and REMOVE only).
     */
    public static final String PENDING_CHANGED = "PENDING_CHANGED";

    private void broadcastPendingChangedAfterCommit(Group group) {
        if (group == null || group.getGroupId() == null || group.getGroupId().isBlank()) return;
        GroupMembershipFrame frame = new GroupMembershipFrame(PENDING_CHANGED, null, null,
                group.getUpdatedAt() != null ? group.getUpdatedAt() : Instant.now());
        afterCommitOrNow(() -> webSocketMessageSender.sendGroupMembership(group.getGroupId(), frame));
    }

    // ---------------- permissions & set helpers ----------------

    /** Return a new/updated set with the value added, never null. */
    private static Set<String> addToSet(Set<String> set, String value) {
        Set<String> out = (set == null) ? new HashSet<>() : new HashSet<>(set);
        if (value != null) out.add(value);
        return out;
    }

    /** Return a new/updated set with the value removed, never null. */
    private static Set<String> removeFromSet(Set<String> set, String value) {
        Set<String> out = (set == null) ? new HashSet<>() : new HashSet<>(set);
        if (value != null) out.remove(value);
        return out;
    }
}
