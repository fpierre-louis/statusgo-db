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

    public GroupService(GroupRepo groupRepo,
                        UserInfoRepo userInfoRepo,
                        WebSocketMessageSender webSocketMessageSender,
                        HouseholdEventService householdEventService,
                        NotificationService notificationService) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.webSocketMessageSender = webSocketMessageSender;
        this.householdEventService = householdEventService;
        this.notificationService = notificationService;
    }

    /**
     * Backend no longer uses SecurityContext to determine the current admin.
     * Frontend is responsible for filtering groups by admin email.
     */
    public List<Group> getGroupsForCurrentAdmin() {
        return groupRepo.findAll();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /*** Existing broadcast methods preserved below ***/

    /**
     * Presence-aware broadcast for generic group status changes (not the "Active alert" flow).
     * Uses NotificationService.deliverPresenceAware so online members get an in-app banner
     * and offline members get FCM / APNs.
     */
    public void broadcastGroupStatusChange(String groupId, String newStatus) {
        Optional<Group> groupOpt = groupRepo.findByGroupId(groupId);
        if (groupOpt.isEmpty()) return;

        Group group = groupOpt.get();
        List<String> memberEmails = group.getMemberEmails();
        if (memberEmails == null || memberEmails.isEmpty()) return;

        List<UserInfo> recipients = userInfoRepo.findByUserEmailIn(memberEmails);

        String senderName = (group.getOwnerName() != null && !group.getOwnerName().isEmpty())
                ? group.getOwnerName()
                : "Group Admin";

        String message = "🚨 " + senderName + " changed the group's status to " + newStatus;
        String targetUrl = "/groups/" + groupId;

        for (UserInfo recipient : recipients) {
            notificationService.deliverPresenceAware(
                    recipient.getUserEmail(),
                    group.getGroupName(),
                    message,
                    senderName,
                    "/images/group-alert-icon.png",
                    "group_status",
                    group.getGroupId(),
                    targetUrl,
                    null,
                    recipient.getFcmtoken()
            );
        }
    }

    public Group createGroup(Group group) {
        if (group.getGroupId() == null || group.getGroupId().isEmpty()) {
            throw new RuntimeException("Missing groupId. Ensure UUID is generated on frontend.");
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

    /**
     * Case-insensitive uniqueness check for the group-create flows.
     * Backs the {@code GET /api/groups/availability} endpoint;
     * replaces the previous FE-side "fetch all + scan in memory"
     * pattern that ran on every keystroke.
     */
    public boolean isGroupNameTaken(String name) {
        if (name == null || name.isBlank()) return false;
        return groupRepo.existsByGroupNameIgnoreCase(name);
    }

    public boolean isGroupCodeTaken(String code) {
        if (code == null || code.isBlank()) return false;
        return groupRepo.existsByGroupCodeIgnoreCase(code);
    }

    public List<Group> getGroupsByAdminEmail(String adminEmail) {
        return groupRepo.findByAdminEmailsContaining(adminEmail);
    }

    public Group getGroupByPublicId(String groupId) {
        return groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found for groupId: " + groupId));
    }

    /**
     * Send a non-emergency "please check in" ping to a group's members
     * without flipping the group's alert state. Phase 1 of
     * {@code docs/BUSINESS_MODEL.md} — the family check-in primitive.
     *
     * <p>Authorization: the caller must be a member of the group. For
     * <b>household</b> groups any member can ping (it's the family —
     * "hey everyone check in" is benign). For non-household groups
     * (school / business / neighborhood) it's restricted to
     * admins/owners so a single member can't blast a 500-person org.</p>
     *
     * <p>Throws {@link SecurityException} on an authorization failure —
     * the resource layer maps that to HTTP 403.</p>
     */
    public void requestCheckIn(String groupId, String callerEmail) {
        if (callerEmail == null || callerEmail.isBlank()) {
            throw new SecurityException("Sign in to request a check-in");
        }
        Group group = getGroupByPublicId(groupId);
        String me = callerEmail.trim().toLowerCase();

        boolean isMember = group.getMemberEmails() != null && group.getMemberEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(me));
        if (!isMember) {
            throw new SecurityException("Only members can request a check-in");
        }

        boolean isHousehold = HouseholdEventService.HOUSEHOLD_GROUP_TYPE
                .equalsIgnoreCase(group.getGroupType());
        if (!isHousehold) {
            boolean isAdmin = group.getAdminEmails() != null && group.getAdminEmails().stream()
                    .anyMatch(e -> e != null && e.equalsIgnoreCase(me));
            boolean isOwner = group.getOwnerEmail() != null
                    && group.getOwnerEmail().equalsIgnoreCase(me);
            if (!isAdmin && !isOwner) {
                throw new SecurityException(
                        "Only admins can request a check-in for this group");
            }
        }

        String callerName = userInfoRepo.findByUserEmailIgnoreCase(callerEmail)
                .map(UserInfo::getUserFirstName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);

        notificationService.notifyCheckInRequest(group, callerEmail, callerName);
    }

    @Transactional(readOnly = true)
    public CheckInRollupDto getCheckInRollup(String groupId) {
        Group group = getGroupByPublicId(groupId);
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

        Set<String> missingEmails = rollup.members().stream()
                .filter(m -> !m.accounted())
                .map(CheckInRollupDto.Member::email)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (missingEmails.isEmpty()) return rollup;

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(new ArrayList<>(missingEmails));
        String title = group.getGroupName() != null ? group.getGroupName() : "Check in";
        String body = callerName + " is checking who is safe. Tap to share your status.";
        String targetUrl = GroupUrlUtil.getGroupTargetUrl(group);
        for (UserInfo user : users) {
            if (user.getUserEmail() == null) continue;
            notificationService.deliverPresenceAwareForGroup(
                    user.getUserEmail(),
                    title,
                    body,
                    callerName,
                    "/images/group-alert-icon.png",
                    "check_in_request",
                    group.getGroupId(),
                    targetUrl,
                    null,
                    user.getFcmtoken(),
                    group.getGroupId(),
                    Category.CHECK_IN_REQUEST
            );
        }
        logger.info("Pinged {} missing check-in member(s) for group {}",
                users.size(), group.getGroupId());
        return rollup;
    }

    private CheckInRollupDto buildCheckInRollup(Group group) {
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

        boolean active = "Active".equalsIgnoreCase(group.getAlert());
        Instant startedAt = group.getAlertActivatedAt() != null
                ? group.getAlertActivatedAt()
                : group.getUpdatedAt();

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
                members
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

        Double lat = group.getLatitude();
        Double lng = group.getLongitude();
        int adminCount = group.getAdminEmails() == null ? 0 : group.getAdminEmails().size();
        // Accurate count from the member list (the denormalized
        // Group.memberCount drifts and isn't kept in sync on join/leave).
        int memberCount = group.getMemberEmails() == null ? 0 : group.getMemberEmails().size();
        boolean alertActive = "Active".equalsIgnoreCase(group.getAlert());

        return new io.sitprep.sitprepapi.dto.GroupPreviewDto(
                group.getGroupId(),
                group.getGroupName(),
                group.getGroupType(),
                group.getDescription(),
                group.getPrivacy(),
                group.getOwnerName(),
                adminCount,
                memberCount,
                group.getAddress(),
                lat,
                lng,
                group.getCreatedAt(),
                alertActive,
                status
        );
    }

    private static boolean containsIgnoreCase(java.util.Collection<String> coll, String needle) {
        if (coll == null || needle == null || needle.isEmpty()) return false;
        for (String s : coll) {
            if (s != null && s.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }


    @Transactional
    public Group updateGroupByPublicId(String groupId, Group groupDetails) {
        Group group = getGroupByPublicId(groupId);
        String previousAlert = group.getAlert();
        updateGroupFields(group, groupDetails);
        Group saved = groupRepo.save(group);
        if (!sameAlertState(previousAlert, saved.getAlert())) {
            GroupAlertFrame frame = new GroupAlertFrame(
                    saved.getGroupId(),
                    frameAlert(saved.getAlert()),
                    saved.getAlertActivatedAt(),
                    initiatedBy(saved),
                    "manual"
            );
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    webSocketMessageSender.sendGroupAlertStatus(saved.getGroupId(), frame);
                }
            });
        }
        return saved;
    }

    public void deleteGroupByPublicId(String groupId) {
        Group group = getGroupByPublicId(groupId);
        groupRepo.delete(group);
    }

    private void updateGroupFields(Group group, Group groupDetails) {
        Set<String> oldMemberEmails = new HashSet<>(safeList(group.getMemberEmails()));
        Set<String> oldPendingMemberEmails = new HashSet<>(safeList(group.getPendingMemberEmails()));

        final String previousAlert = group.getAlert();
        final String newAlert = groupDetails.getAlert();
        final boolean alertChanged = !Objects.equals(previousAlert, newAlert);
        final boolean alertBecameActive = alertChanged && "Active".equalsIgnoreCase(newAlert);
        final boolean alertBecameInactive = alertChanged
                && "Active".equalsIgnoreCase(previousAlert)
                && !"Active".equalsIgnoreCase(newAlert);
        final boolean isHousehold =
                HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(group.getGroupType());

        group.setAdminEmails(safeList(groupDetails.getAdminEmails()));
        group.setAlert(groupDetails.getAlert());
        group.setCreatedAt(groupDetails.getCreatedAt());
        group.setDescription(groupDetails.getDescription());
        group.setGroupName(groupDetails.getGroupName());
        group.setGroupType(groupDetails.getGroupType());
        group.setLastUpdatedBy(groupDetails.getLastUpdatedBy());
        group.setMemberCount(groupDetails.getMemberCount());
        group.setMemberEmails(safeList(groupDetails.getMemberEmails()));
        group.setPendingMemberEmails(safeList(groupDetails.getPendingMemberEmails()));
        group.setPrivacy(groupDetails.getPrivacy());
        group.setSubGroupIDs(safeList(groupDetails.getSubGroupIDs()));
        group.setParentGroupIDs(safeList(groupDetails.getParentGroupIDs()));
        group.setUpdatedAt(Instant.now());
        group.setZipCode(groupDetails.getZipCode());
        group.setOwnerName(groupDetails.getOwnerName());
        group.setOwnerEmail(groupDetails.getOwnerEmail());
        group.setGroupCode(groupDetails.getGroupCode());
        group.setAddress(groupDetails.getAddress());
        group.setLongitude(groupDetails.getLongitude());
        group.setLatitude(groupDetails.getLatitude());

        // Track when the alert most recently went Active so the decay
        // sweep can find stale ones. Cleared on flip-back so a future
        // re-activation gets a fresh timestamp instead of inheriting the
        // prior session's clock. Reminder counter resets on both
        // transitions so a re-activation gets the full 5-reminder
        // cadence again, and a manual end stops any pending tick from
        // sending a stale reminder.
        if (alertBecameActive) {
            group.setAlertActivatedAt(Instant.now());
            group.setCheckInRemindersFired(0);
        } else if (alertBecameInactive) {
            group.setAlertActivatedAt(null);
            group.setCheckInRemindersFired(0);
        }

        if (alertBecameActive) {
            notifyGroupMembers(group);
        }

        if (isHousehold && alertChanged) {
            String actor = groupDetails.getLastUpdatedBy();
            if (alertBecameActive) {
                householdEventService.recordCheckinStarted(group.getGroupId(), actor);
            } else if (alertBecameInactive) {
                householdEventService.recordCheckinEnded(group.getGroupId(), actor);
            }
        }

        notifyNewMembers(group, oldMemberEmails);
        notifyAdminsOfNewMembers(group, oldMemberEmails);
        notifyAdminsOfPendingMembers(group, oldPendingMemberEmails);
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

        for (UserInfo admin : admins) {
            String token = admin.getFcmtoken();

            for (String email : newPendingMemberEmails) {
                UserInfo pending = userInfoRepo.findByUserEmailIgnoreCase(email)
                        .orElseThrow(() -> new RuntimeException("User not found: " + email));

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
                        token
                );
            }
        }
    }

    private void notifyNewMembers(Group group, Set<String> oldMemberEmails) {
        Set<String> newMemberEmails = new HashSet<>(safeList(group.getMemberEmails()));
        newMemberEmails.removeAll(oldMemberEmails);
        if (newMemberEmails.isEmpty()) return;

        String targetUrl = GroupUrlUtil.getGroupTargetUrl(group);

        for (String email : newMemberEmails) {
            UserInfo user = userInfoRepo.findByUserEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found: " + email));

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

        for (String email : newMemberEmails) {
            UserInfo newUser = userInfoRepo.findByUserEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found: " + email));

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
                        admin.getUserEmail()
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
        boolean isPrivate = "Private".equalsIgnoreCase(g.getPrivacy());

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
            return saved;
        }

        // Public: instant-join.
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
        broadcastMembershipAfterCommit(saved, "ADD", email, GroupRole.MEMBER.wire(), saved.getUpdatedAt());
        return saved;
    }

    private static boolean containsCaseInsensitive(java.util.Collection<String> coll, String needle) {
        if (coll == null || needle == null || needle.isEmpty()) return false;
        for (String s : coll) {
            if (s != null && s.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }

    @Transactional
    public Group approveMember(String groupId, String email) {
        approveMemberAction(groupId, email);
        return getGroupByPublicId(groupId);
    }

    @Transactional
    public GroupMembershipActionResultDto approveMemberAction(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        requireAdminOrOwner(g); // now a no-op; frontend enforces
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
            members.add(normalizedEmail);
        }

        g.setMemberEmails(members);
        g.setPendingMemberEmails(pending);
        g.setMemberCount(members.size());
        g.setUpdatedAt(Instant.now());

        syncJoinedGroup(normalizedEmail, groupId);

        Group saved = groupRepo.save(g);
        if (!alreadyMember) {
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
        requireAdminOrOwner(g); // no-op
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

    @Transactional
    public Group removeMember(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        requireAdminOrOwner(g); // no-op

        List<String> members = safeList(g.getMemberEmails());
        List<String> admins = safeList(g.getAdminEmails());
        boolean wasMember = containsCaseInsensitive(members, email);
        String previousRole = GroupRole.fromGroup(g, email).wire();

        // null-safe removals
        members.removeIf(e -> e != null && e.trim().equalsIgnoreCase(email));
        admins.removeIf(e -> e != null && e.trim().equalsIgnoreCase(email));

        g.setMemberEmails(members);
        g.setAdminEmails(admins);
        g.setMemberCount(members.size());
        g.setUpdatedAt(Instant.now());

        userInfoRepo.findByUserEmail(email).ifPresent(u -> {
            Set<String> j = removeFromSet(u.getJoinedGroupIDs(), groupId);
            Set<String> m = removeFromSet(u.getManagedGroupIDs(), groupId);
            u.setJoinedGroupIDs(j);
            u.setManagedGroupIDs(m);
            userInfoRepo.save(u);
        });

        Group saved = groupRepo.save(g);
        if (wasMember) {
            broadcastMembershipAfterCommit(saved, "REMOVE", email, previousRole, saved.getUpdatedAt());
        }
        return saved;
    }

    @Transactional
    public Group addAdmin(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        requireAdminOrOwner(g); // no-op

        List<String> admins = safeList(g.getAdminEmails());
        // null-safe admin check
        boolean alreadyAdmin = admins.stream()
                .anyMatch(e -> e != null && e.trim().equalsIgnoreCase(email));
        if (!alreadyAdmin) {
            admins.add(email);
        }

        List<String> members = safeList(g.getMemberEmails());
        // null-safe membership check
        boolean alreadyMember = members.stream()
                .anyMatch(e -> e != null && e.trim().equalsIgnoreCase(email));
        if (!alreadyMember) {
            members.add(email); // admin must be a member too
        }

        g.setAdminEmails(admins);
        g.setMemberEmails(members);
        g.setMemberCount(members.size());
        g.setUpdatedAt(Instant.now());

        userInfoRepo.findByUserEmail(email).ifPresent(u -> {
            Set<String> m = addToSet(u.getManagedGroupIDs(), groupId);
            Set<String> j = addToSet(u.getJoinedGroupIDs(), groupId);
            u.setManagedGroupIDs(m);
            u.setJoinedGroupIDs(j);
            userInfoRepo.save(u);
        });

        Group saved = groupRepo.save(g);
        if (!alreadyMember) {
            broadcastMembershipAfterCommit(
                    saved,
                    "ADD",
                    email,
                    GroupRole.fromGroup(saved, email).wire(),
                    saved.getUpdatedAt());
        }
        return saved;
    }

    /**
     * Set the organization plan tier (Phase 4 of docs/BUSINESS_MODEL.md).
     * Self-serve and unpaid for now — Stripe billing lands later; this
     * just records the chosen tier. Caller authorization (admin/owner)
     * is enforced at the resource layer.
     *
     * @param tier a {@link PlanTier} enum name; invalid / null input
     *             normalizes to {@code FREE} via {@link PlanTier#fromWire}.
     */
    @Transactional
    public Group setPlanTier(String groupId, String tier) {
        Group g = getGroupByPublicId(groupId);
        g.setPlanTier(PlanTier.fromWire(tier).name());
        g.setUpdatedAt(Instant.now());
        return groupRepo.save(g);
    }

    /**
     * Set (or clear) the group's custom logo URL — Phase 4 of
     * docs/BUSINESS_MODEL.md ("co-branded page"). A null / blank value
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

    @Transactional
    public Group removeAdmin(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        requireAdminOrOwner(g); // no-op

        // owner cannot be demoted here (still kept for safety)
        if (g.getOwnerEmail() != null && g.getOwnerEmail().equalsIgnoreCase(email)) {
            throw new SecurityException("Cannot remove owner admin role. Transfer ownership first.");
        }

        List<String> admins = safeList(g.getAdminEmails());
        // null-safe removal
        admins.removeIf(e -> e != null && e.trim().equalsIgnoreCase(email));
        g.setAdminEmails(admins);
        g.setUpdatedAt(Instant.now());

        userInfoRepo.findByUserEmail(email).ifPresent(u -> {
            Set<String> m = removeFromSet(u.getManagedGroupIDs(), groupId);
            u.setManagedGroupIDs(m);
            userInfoRepo.save(u);
        });

        return groupRepo.save(g);
    }

    @Transactional
    public Group transferOwner(String groupId, String newOwnerEmail) {
        Group g = getGroupByPublicId(groupId);
        requireOwner(g); // no-op now

        g.setOwnerEmail(newOwnerEmail);

        // ensure new owner is admin + member
        List<String> admins = safeList(g.getAdminEmails());
        boolean alreadyAdmin = admins.stream()
                .anyMatch(e -> e != null && e.trim().equalsIgnoreCase(newOwnerEmail));
        if (!alreadyAdmin) {
            admins.add(newOwnerEmail);
        }
        g.setAdminEmails(admins);

        List<String> members = safeList(g.getMemberEmails());
        boolean alreadyMember = members.stream()
                .anyMatch(e -> e != null && e.trim().equalsIgnoreCase(newOwnerEmail));
        if (!alreadyMember) {
            members.add(newOwnerEmail);
        }
        g.setMemberEmails(members);
        g.setMemberCount(members.size());

        g.setUpdatedAt(Instant.now());

        // sync user managed/joined sets
        userInfoRepo.findByUserEmail(newOwnerEmail).ifPresent(u -> {
            Set<String> m = addToSet(u.getManagedGroupIDs(), groupId);
            Set<String> j = addToSet(u.getJoinedGroupIDs(), groupId);
            u.setManagedGroupIDs(m);
            u.setJoinedGroupIDs(j);
            userInfoRepo.save(u);
        });

        Group saved = groupRepo.save(g);
        if (!alreadyMember) {
            broadcastMembershipAfterCommit(
                    saved,
                    "ADD",
                    newOwnerEmail,
                    GroupRole.fromGroup(saved, newOwnerEmail).wire(),
                    saved.getUpdatedAt());
        }
        return saved;
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
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                webSocketMessageSender.sendGroupMembership(group.getGroupId(), frame);
            }
        });
    }

    // ---------------- permissions & set helpers ----------------

    /**
     * No-op: authorization is handled on the frontend now.
     */
    private void requireAdminOrOwner(Group g) {
        // intentionally empty
    }

    /**
     * No-op: authorization is handled on the frontend now.
     */
    private void requireOwner(Group g) {
        // intentionally empty
    }

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
