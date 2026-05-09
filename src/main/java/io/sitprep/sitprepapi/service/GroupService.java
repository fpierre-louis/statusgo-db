package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.GroupUrlUtil;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

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
        return groupRepo.save(group);
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

        Double lat = parseCoord(group.getLatitude());
        Double lng = parseCoord(group.getLongitude());
        int adminCount = group.getAdminEmails() == null ? 0 : group.getAdminEmails().size();
        int memberCount = group.getMemberCount() != null
                ? group.getMemberCount()
                : (group.getMemberEmails() == null ? 0 : group.getMemberEmails().size());
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

    private static Double parseCoord(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Double.parseDouble(raw.trim()); } catch (NumberFormatException e) { return null; }
    }

    public Group updateGroupByPublicId(String groupId, Group groupDetails) {
        Group group = getGroupByPublicId(groupId);
        updateGroupFields(group, groupDetails);
        return groupRepo.save(group);
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

        List<UserInfo> admins = userInfoRepo.findByUserEmailIn(group.getAdminEmails());
        String targetUrl = GroupUrlUtil.getGroupTargetUrl(group);

        for (UserInfo admin : admins) {
            String token = admin.getFcmtoken();
            if (token == null || token.isEmpty()) continue;

            for (String email : newPendingMemberEmails) {
                UserInfo pending = userInfoRepo.findByUserEmail(email)
                        .orElseThrow(() -> new RuntimeException("User not found: " + email));

                notificationService.sendNotification(
                        "Hi " + admin.getUserFirstName() + "👋",
                        "A new request from " + pending.getUserFirstName() + " " + pending.getUserLastName() +
                                " is pending for your group, " + group.getGroupName() + ".",
                        "Admin",
                        "/images/admin-icon.png",
                        Set.of(token),
                        "pending_member",
                        group.getGroupId(),
                        targetUrl,
                        // additionalData carries the pending requester's email so
                        // the iOS PENDING_MEMBER notification action buttons (Approve /
                        // Decline) can call approveMember(groupId, email) /
                        // removeMember(groupId, email) directly. See
                        // src/shared/notifications/NotificationActionDispatcher.jsx.
                        pending.getUserEmail(),
                        admin.getUserEmail()
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
                    user.getProfileImageURL() != null ? user.getProfileImageURL() : "/images/default-user-icon.png",
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

        List<UserInfo> admins = userInfoRepo.findByUserEmailIn(group.getAdminEmails());
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
                        newUser.getProfileImageURL() != null ? newUser.getProfileImageURL() : "/images/default-user-icon.png",
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

        return groupRepo.save(g);
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
        Group g = getGroupByPublicId(groupId);
        requireAdminOrOwner(g); // now a no-op; frontend enforces

        List<String> members = safeList(g.getMemberEmails());
        List<String> pending = safeList(g.getPendingMemberEmails());

        // null-safe removal of pending email
        pending.removeIf(e -> e != null && e.trim().equalsIgnoreCase(email));

        // null-safe membership check
        boolean alreadyMember = members.stream()
                .anyMatch(e -> e != null && e.trim().equalsIgnoreCase(email));
        if (!alreadyMember) {
            members.add(email);
        }

        g.setMemberEmails(members);
        g.setPendingMemberEmails(pending);
        g.setUpdatedAt(Instant.now());

        // sync user.joinedGroupIDs
        userInfoRepo.findByUserEmail(email).ifPresent(u -> {
            Set<String> updated = addToSet(u.getJoinedGroupIDs(), groupId);
            u.setJoinedGroupIDs(updated);
            userInfoRepo.save(u);
        });

        return groupRepo.save(g);
    }

    @Transactional
    public Group rejectPendingMember(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        requireAdminOrOwner(g); // no-op

        List<String> pending = safeList(g.getPendingMemberEmails());
        // null-safe removal
        pending.removeIf(e -> e != null && e.trim().equalsIgnoreCase(email));

        g.setPendingMemberEmails(pending);
        g.setUpdatedAt(Instant.now());
        return groupRepo.save(g);
    }

    @Transactional
    public Group removeMember(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        requireAdminOrOwner(g); // no-op

        List<String> members = safeList(g.getMemberEmails());
        List<String> admins = safeList(g.getAdminEmails());

        // null-safe removals
        members.removeIf(e -> e != null && e.trim().equalsIgnoreCase(email));
        admins.removeIf(e -> e != null && e.trim().equalsIgnoreCase(email));

        g.setMemberEmails(members);
        g.setAdminEmails(admins);
        g.setUpdatedAt(Instant.now());

        userInfoRepo.findByUserEmail(email).ifPresent(u -> {
            Set<String> j = removeFromSet(u.getJoinedGroupIDs(), groupId);
            Set<String> m = removeFromSet(u.getManagedGroupIDs(), groupId);
            u.setJoinedGroupIDs(j);
            u.setManagedGroupIDs(m);
            userInfoRepo.save(u);
        });

        return groupRepo.save(g);
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
        g.setUpdatedAt(Instant.now());

        userInfoRepo.findByUserEmail(email).ifPresent(u -> {
            Set<String> m = addToSet(u.getManagedGroupIDs(), groupId);
            Set<String> j = addToSet(u.getJoinedGroupIDs(), groupId);
            u.setManagedGroupIDs(m);
            u.setJoinedGroupIDs(j);
            userInfoRepo.save(u);
        });

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

        g.setUpdatedAt(Instant.now());

        // sync user managed/joined sets
        userInfoRepo.findByUserEmail(newOwnerEmail).ifPresent(u -> {
            Set<String> m = addToSet(u.getManagedGroupIDs(), groupId);
            Set<String> j = addToSet(u.getJoinedGroupIDs(), groupId);
            u.setManagedGroupIDs(m);
            u.setJoinedGroupIDs(j);
            userInfoRepo.save(u);
        });

        return groupRepo.save(g);
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