package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private static final Logger logger = LoggerFactory.getLogger(GroupService.class);

    @Autowired
    private GroupRepo groupRepo;

    @Autowired
    private UserInfoRepo userInfoRepo;

    @Autowired
    private NotificationService notificationService;

    // ✅ CRUD Operations Using Public UUID

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

    // ✅ Helper Method to Update Group Fields
    private void updateGroupFields(Group group, Group groupDetails) {
        Set<String> oldMemberEmails = new HashSet<>(group.getMemberEmails());
        Set<String> oldPendingMemberEmails = new HashSet<>(group.getPendingMemberEmails());
        boolean alertChangedToActive = !"Active".equals(group.getAlert()) && "Active".equals(groupDetails.getAlert());

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

        if (alertChangedToActive) {
            notifyGroupMembers(group);
        }

        notifyNewMembers(group, oldMemberEmails);
        notifyAdminsOfNewMembers(group, oldMemberEmails);
        notifyAdminsOfPendingMembers(group, oldPendingMemberEmails);
    }

    // Helper to determine the correct group URL based on groupType
    // ✅ FIX: Change to public access modifier
    public String getGroupTargetUrl(Group group) {
        if ("Household".equalsIgnoreCase(group.getGroupType())) {
            return "/household/h/4D-FwtX/household/" + group.getGroupId();
        } else {
            return "/Linked/lg/4D-FwtX/" + group.getGroupId();
        }
    }


    // ✅ Notification Methods (use UUID groupId)
    private void notifyGroupMembers(Group group) {
        List<String> memberEmails = group.getMemberEmails();
        List<UserInfo> users = userInfoRepo.findByUserEmailIn(memberEmails);

        Set<String> tokens = users.stream()
                .map(UserInfo::getFcmtoken)
                .filter(token -> token != null && !token.isEmpty())
                .collect(Collectors.toSet());

        if (tokens.isEmpty()) {
            logger.warn("No FCM tokens found for group members.");
            return;
        }

        String owner = group.getOwnerName() != null ? group.getOwnerName() : "your group leader";
        notificationService.sendNotification(
                group.getGroupName(),
                "🚨 Important: " + owner + " here! Checking in on you. Click here and let me know your status.",
                "Group Alert",
                "/images/group-alert-icon.png",
                tokens,
                "alert",
                group.getGroupId(),           // ✅ Use UUID directly
                "/status-now",
                null
        );
    }

    private void notifyAdminsOfPendingMembers(Group group, Set<String> oldPendingMemberEmails) {
        Set<String> newPendingMemberEmails = new HashSet<>(group.getPendingMemberEmails());
        newPendingMemberEmails.removeAll(oldPendingMemberEmails);

        if (newPendingMemberEmails.isEmpty()) return;

        List<UserInfo> admins = userInfoRepo.findByUserEmailIn(group.getAdminEmails());

        String targetUrl = getGroupTargetUrl(group); // Determine URL based on group type

        for (UserInfo admin : admins) {
            String token = admin.getFcmtoken();
            if (token == null || token.isEmpty()) continue;

            for (String newPendingMemberEmail : newPendingMemberEmails) {
                UserInfo pendingMember = userInfoRepo.findByUserEmail(newPendingMemberEmail)
                        .orElseThrow(() -> new RuntimeException("User not found: " + newPendingMemberEmail));

                notificationService.sendNotification(
                        "Hi " + admin.getUserFirstName() + "👋",
                        "A new request from " + pendingMember.getUserFirstName() + " " + pendingMember.getUserLastName() +
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

        String targetUrl = getGroupTargetUrl(group); // Determine URL based on group type

        for (String newMemberEmail : newMemberEmails) {
            UserInfo newMember = userInfoRepo.findByUserEmail(newMemberEmail)
                    .orElseThrow(() -> new RuntimeException("User not found: " + newMemberEmail));

            String token = newMember.getFcmtoken();
            if (token == null || token.isEmpty()) continue;

            String notificationBody = "Hi " + newMember.getUserFirstName() + "👋, you've joined " + group.getGroupName() + ". Welcome!";
            notificationService.sendNotification(
                    "Welcome to " + group.getGroupName() + "!",
                    notificationBody,
                    "User",
                    newMember.getProfileImageURL() != null ? newMember.getProfileImageURL() : "/images/default-user-icon.png",
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

        String targetUrl = getGroupTargetUrl(group); // Determine URL based on group type

        for (String newMemberEmail : newMemberEmails) {
            UserInfo newMember = userInfoRepo.findByUserEmail(newMemberEmail)
                    .orElseThrow(() -> new RuntimeException("User not found: " + newMemberEmail));

            for (UserInfo admin : admins) {
                String token = admin.getFcmtoken();
                if (token == null || token.isEmpty()) continue;

                String notificationBody = newMember.getUserFirstName() + " " + newMember.getUserLastName() +
                        " has joined " + group.getGroupName() + ". Say hello!";

                notificationService.sendNotification(
                        "Hi " + admin.getUserFirstName() + "👋",
                        notificationBody,
                        "Admin",
                        newMember.getProfileImageURL() != null ? newMember.getProfileImageURL() : "/images/default-user-icon.png",
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