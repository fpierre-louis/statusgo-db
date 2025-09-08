package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
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
    private NotificationService notificationService; // setter-injected

    public GroupService(GroupRepo groupRepo,
                        UserInfoRepo userInfoRepo,
                        WebSocketMessageSender webSocketMessageSender,
                        NotificationService notificationService) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.webSocketMessageSender = webSocketMessageSender;
        this.notificationService = notificationService;
    }

    public List<Group> getGroupsForCurrentAdmin() {
        String email = AuthUtils.getCurrentUserEmail();
        return groupRepo.findByAdminEmailsContaining(email);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /*** Existing broadcast methods preserved below ***/

    public void broadcastGroupStatusChange(String groupId, String newStatus) {
        String initiatedByEmail = AuthUtils.getCurrentUserEmail();

        Optional<Group> groupOpt = groupRepo.findByGroupId(groupId);
        if (groupOpt.isEmpty()) return;

        Group group = groupOpt.get();
        List<String> memberEmails = group.getMemberEmails();
        if (memberEmails == null || memberEmails.isEmpty()) return;

        List<UserInfo> recipients = userInfoRepo.findByUserEmailIn(memberEmails);
        Optional<UserInfo> senderOpt = userInfoRepo.findByUserEmail(initiatedByEmail);

        String senderName = senderOpt
                .map(u -> u.getUserFirstName() + " " + u.getUserLastName())
                .orElse("Group Admin");

        String message = "🚨 " + senderName + " changed the group's status to " + newStatus;

        for (UserInfo recipient : recipients) {
            if (recipient.getUserEmail().equalsIgnoreCase(initiatedByEmail)) continue;

            NotificationPayload payload = new NotificationPayload(
                    recipient.getUserEmail(),
                    group.getGroupName(),
                    message,
                    "/images/group-alert-icon.png",
                    "group_status",
                    "/groups/" + groupId,
                    groupId,
                    Instant.now()
            );
            webSocketMessageSender.sendInAppNotification(payload);

            String token = recipient.getFcmtoken();
            if (token != null && !token.isEmpty()) {
                notificationService.sendNotification(
                        group.getGroupName(),
                        message,
                        senderName,
                        "/images/group-alert-icon.png",
                        Set.of(token),
                        "group_status",
                        groupId,
                        "/groups/" + groupId,
                        null,
                        recipient.getUserEmail()
                );
            }
        }
    }

    public Group createGroup(Group group) {
        if (group.getGroupId() == null || group.getGroupId().isEmpty()) {
            throw new RuntimeException("Missing groupId. Ensure UUID is generated on frontend.");
        }
        return groupRepo.save(group);
    }

    public List<Group> getAllGroups() { return groupRepo.findAll(); }

    public List<Group> getGroupsByAdminEmail(String adminEmail) {
        return groupRepo.findByAdminEmailsContaining(adminEmail);
    }

    public Group getGroupByPublicId(String groupId) {
        return groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found for groupId: " + groupId));
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
        group.setPrivacy(groupDetails.getPrivacy()); // <-- fixed: removed extra ')'
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

        if (alertBecameActive) {
            notifyGroupMembers(group);
        }

        notifyNewMembers(group, oldMemberEmails);
        notifyAdminsOfNewMembers(group, oldMemberEmails);
        notifyAdminsOfPendingMembers(group, oldPendingMemberEmails);
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
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
                        null,
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

    @Transactional
    public Group approveMember(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        requireAdminOrOwner(g);

        List<String> members = safeList(g.getMemberEmails());
        List<String> pending = safeList(g.getPendingMemberEmails());

        pending.removeIf(e -> e.equalsIgnoreCase(email));
        if (members.stream().noneMatch(e -> e.equalsIgnoreCase(email))) {
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
        requireAdminOrOwner(g);

        List<String> pending = safeList(g.getPendingMemberEmails());
        pending.removeIf(e -> e.equalsIgnoreCase(email));
        g.setPendingMemberEmails(pending);
        g.setUpdatedAt(Instant.now());
        return groupRepo.save(g);
    }

    @Transactional
    public Group removeMember(String groupId, String email) {
        Group g = getGroupByPublicId(groupId);
        requireAdminOrOwner(g);

        List<String> members = safeList(g.getMemberEmails());
        List<String> admins  = safeList(g.getAdminEmails());

        members.removeIf(e -> e.equalsIgnoreCase(email));
        admins.removeIf(e -> e.equalsIgnoreCase(email));

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
        requireAdminOrOwner(g);

        List<String> admins = safeList(g.getAdminEmails());
        if (admins.stream().noneMatch(e -> e.equalsIgnoreCase(email))) {
            admins.add(email);
        }

        List<String> members = safeList(g.getMemberEmails());
        if (members.stream().noneMatch(e -> e.equalsIgnoreCase(email))) {
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
        requireAdminOrOwner(g);

        // owner cannot be demoted here
        if (g.getOwnerEmail() != null && g.getOwnerEmail().equalsIgnoreCase(email)) {
            throw new SecurityException("Cannot remove owner admin role. Transfer ownership first.");
        }

        List<String> admins = safeList(g.getAdminEmails());
        admins.removeIf(e -> e.equalsIgnoreCase(email));
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
        requireOwner(g); // only current owner can transfer

        g.setOwnerEmail(newOwnerEmail);

        // ensure new owner is admin + member
        List<String> admins = safeList(g.getAdminEmails());
        if (admins.stream().noneMatch(e -> e.equalsIgnoreCase(newOwnerEmail))) {
            admins.add(newOwnerEmail);
        }
        g.setAdminEmails(admins);

        List<String> members = safeList(g.getMemberEmails());
        if (members.stream().noneMatch(e -> e.equalsIgnoreCase(newOwnerEmail))) {
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

    private void requireAdminOrOwner(Group g) {
        String requester = AuthUtils.getCurrentUserEmail();
        boolean isOwner = g.getOwnerEmail() != null && g.getOwnerEmail().equalsIgnoreCase(requester);
        boolean isAdmin = safeList(g.getAdminEmails()).stream().anyMatch(e -> e.equalsIgnoreCase(requester));
        if (!(isOwner || isAdmin)) {
            throw new SecurityException("Forbidden: admin/owner only");
        }
    }

    private void requireOwner(Group g) {
        String requester = AuthUtils.getCurrentUserEmail();
        boolean isOwner = g.getOwnerEmail() != null && g.getOwnerEmail().equalsIgnoreCase(requester);
        if (!isOwner) throw new SecurityException("Forbidden: owner only");
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
