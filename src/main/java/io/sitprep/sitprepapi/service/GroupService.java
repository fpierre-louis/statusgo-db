package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.dto.NotificationPayload;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class GroupService {

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final WebSocketMessageSender webSocketMessageSender;
    private final NotificationService notificationService;

    public GroupService(GroupRepo groupRepo, UserInfoRepo userInfoRepo,
                        WebSocketMessageSender webSocketMessageSender,
                        NotificationService notificationService) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.webSocketMessageSender = webSocketMessageSender;
        this.notificationService = notificationService;
    }

    @Autowired
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void broadcastGroupStatusChange(String groupId, String newStatus, String initiatedByEmail) {
        Optional<Group> groupOpt = groupRepo.findByGroupId(groupId);
        if (groupOpt.isEmpty()) return;

        Group group = groupOpt.get();
        List<String> memberEmails = group.getMemberEmails();

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

            notificationService.broadcastNotification(payload);
        }
    }



    public Group createGroup(Group group) {
        if (group.getGroupId() == null || group.getGroupId().isEmpty()) {
            throw new RuntimeException("Missing groupId. Ensure UUID is generated on frontend.");
        }
        return groupRepo.save(group);
    }

    public List<Group> getAllGroups() {
        return groupRepo.findAll();
    }

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
        Set<String> oldMemberEmails = new HashSet<>(group.getMemberEmails());
        Set<String> oldPendingMemberEmails = new HashSet<>(group.getPendingMemberEmails());
        boolean alertChanged = !group.getAlert().equals(groupDetails.getAlert());

        group.setAdminEmails(groupDetails.getAdminEmails());
        group.setAlert(groupDetails.getAlert());
        group.setCreatedAt(groupDetails.getCreatedAt());
        group.setDescription(groupDetails.getDescription());
        group.setGroupName(groupDetails.getGroupName());
        group.setGroupType(groupDetails.getGroupType());
        group.setLastUpdatedBy(groupDetails.getLastUpdatedBy());
        group.setMemberCount(groupDetails.getMemberCount());
        group.setMemberEmails(groupDetails.getMemberEmails());
        group.setPendingMemberEmails(groupDetails.getPendingMemberEmails());
        group.setPrivacy(groupDetails.getPrivacy());
        group.setSubGroupIDs(groupDetails.getSubGroupIDs());
        group.setParentGroupIDs(groupDetails.getParentGroupIDs());
        group.setUpdatedAt(Instant.now());
        group.setZipCode(groupDetails.getZipCode());
        group.setOwnerName(groupDetails.getOwnerName());
        group.setOwnerEmail(groupDetails.getOwnerEmail());
        group.setGroupCode(groupDetails.getGroupCode());
        group.setAddress(groupDetails.getAddress());
        group.setLongitude(groupDetails.getLongitude());
        group.setLatitude(groupDetails.getLatitude());

        if (alertChanged) {
            notifyGroupMembers(group);
        }

        notifyNewMembers(group, oldMemberEmails);
        notifyAdminsOfNewMembers(group, oldMemberEmails);
        notifyAdminsOfPendingMembers(group, oldPendingMemberEmails);
    }

    private void notifyGroupMembers(Group group) {
        if (group.getAlert() == null || group.getAlert().isEmpty()) {
            logger.warn("❌ Skipping group alert notification: Alert value is null or empty");
            return;
        }

        String initiatedBy = group.getLastUpdatedBy() != null
                ? group.getLastUpdatedBy()
                : group.getOwnerEmail();

        logger.info("📣 Alert status change detected for group {}: new status = {}", group.getGroupId(), group.getAlert());

        notificationService.notifyGroupAlertChange(group, group.getAlert(), initiatedBy);
    }



    private void notifyAdminsOfPendingMembers(Group group, Set<String> oldPendingMemberEmails) {
        Set<String> newPendingMemberEmails = new HashSet<>(group.getPendingMemberEmails());
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
                        null
                );
            }
        }
    }

    private void notifyNewMembers(Group group, Set<String> oldMemberEmails) {
        Set<String> newMemberEmails = new HashSet<>(group.getMemberEmails());
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
                    null
            );
        }
    }

    private void notifyAdminsOfNewMembers(Group group, Set<String> oldMemberEmails) {
        Set<String> newMemberEmails = new HashSet<>(group.getMemberEmails());
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
                        null
                );
            }
        }
    }
}
