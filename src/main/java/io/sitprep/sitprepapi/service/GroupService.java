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

import java.time.LocalDateTime;
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

    @Transactional
    public Group updateGroup(Long groupId, Group groupDetails) {
        // Fetch the existing group from the database
        Group group = groupRepo.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found for this id :: " + groupId));

        // Capture the old member emails before making any changes
        Set<String> oldMemberEmails = new HashSet<>(group.getMemberEmails());

        // Capture the old pending member emails before making any changes
        Set<String> oldPendingMemberEmails = new HashSet<>(group.getPendingMemberEmails());

        // Check if the group's alert changed to "Active"
        boolean alertChangedToActive = !"Active".equals(group.getAlert()) && "Active".equals(groupDetails.getAlert());

        // Update group details with the new groupDetails values
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
        group.setUpdatedAt(LocalDateTime.now());
        group.setZipCode(groupDetails.getZipCode());
        group.setOwnerName(groupDetails.getOwnerName());
        group.setOwnerEmail(groupDetails.getOwnerEmail());
        group.setGroupCode(groupDetails.getGroupCode());
        group.setAddress(groupDetails.getAddress());

        // Save the updated group details in the database
        Group updatedGroup = groupRepo.save(group);

        // Notify group members if the alert was changed to "Active"
        if (alertChangedToActive) {
            notifyGroupMembers(updatedGroup); // Add this line to notify members
        }

        // Notify newly added members
        notifyNewMembers(updatedGroup, oldMemberEmails);

        // Notify admins of new members added
        notifyAdminsOfNewMembers(updatedGroup, oldMemberEmails);

        // Notify admins of new pending members
        notifyAdminsOfPendingMembers(updatedGroup, oldPendingMemberEmails);

        return updatedGroup;
    }

    public Group createGroup(Group group) {
        return groupRepo.save(group);
    }

    public void deleteGroup(Long groupId) {
        Group group = groupRepo.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found for this id :: " + groupId));
        groupRepo.delete(group);
    }

    public List<Group> getGroupsByAdminEmail(String adminEmail) {
        return groupRepo.findByAdminEmail(adminEmail);
    }

    public List<Group> getAllGroups() {
        return groupRepo.findAll();
    }

    public Group getGroupById(Long groupId) {
        return groupRepo.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found for this id :: " + groupId));
    }

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
        String notificationBody = "🚨 Important: " + owner + " here! Checking in on you. Click here and let me know your status.";

        try {
            notificationService.sendNotification(
                    group.getGroupName(),
                    notificationBody,
                    "Group Alert",
                    tokens,
                    "alert",
                    String.valueOf(group.getGroupId())
            );
        } catch (Exception e) {
            logger.error("Error sending notification: ", e);
        }
    }

    private void notifyAdminsOfPendingMembers(Group group, Set<String> oldPendingMemberEmails) {
        // Get the current pending member emails
        Set<String> newPendingMemberEmails = new HashSet<>(group.getPendingMemberEmails());

        // Find the new pending members by removing the old ones from the current list
        newPendingMemberEmails.removeAll(oldPendingMemberEmails);

        // If there are no new pending members, exit the method
        if (newPendingMemberEmails.isEmpty()) {
            return;
        }

        // Log the pending member addition
        logger.info("New pending members detected in group {}: {}", group.getGroupName(), newPendingMemberEmails);

        // Get the group admins' email addresses
        List<String> adminEmails = group.getAdminEmails();
        List<UserInfo> admins = userInfoRepo.findByUserEmailIn(adminEmails);

        // Notify each admin about the new pending member(s)
        for (UserInfo admin : admins) {
            String token = admin.getFcmtoken();
            if (token == null || token.isEmpty()) {
                logger.warn("No FCM token found for admin: {}", admin.getUserEmail());
                continue;
            }

            for (String newPendingMemberEmail : newPendingMemberEmails) {
                UserInfo newPendingMember = userInfoRepo.findByUserEmail(newPendingMemberEmail)
                        .orElseThrow(() -> new RuntimeException("User not found: " + newPendingMemberEmail));

                // Customize the notification message for pending members
                String notificationTitle = "Hi " + admin.getUserFirstName() + "👋";
                String notificationBody = "A new member request from " + newPendingMember.getUserFirstName()
                        + " " + newPendingMember.getUserLastName() + " is pending approval for your group, "
                        + group.getGroupName() + ". Please review their request.";

                try {
                    // Send notification to the admin
                    notificationService.sendNotification(notificationTitle, notificationBody, "Admin",
                            Set.of(token), "pending_member", String.valueOf(group.getGroupId()));

                    // Log the notification event
                    logger.info("Notification sent to admin {} for new pending member {} in group {}",
                            admin.getUserEmail(), newPendingMemberEmail, group.getGroupName());
                } catch (Exception e) {
                    logger.error("Error sending notification: ", e);
                }
            }
        }
    }

    private void notifyNewMembers(Group group, Set<String> oldMemberEmails) {
        Set<String> newMemberEmails = new HashSet<>(group.getMemberEmails());
        newMemberEmails.removeAll(oldMemberEmails); // These are the newly added members

        if (newMemberEmails.isEmpty()) {
            return;
        }

        // Notify the newly added members
        for (String newMemberEmail : newMemberEmails) {
            UserInfo newMember = userInfoRepo.findByUserEmail(newMemberEmail)
                    .orElseThrow(() -> new RuntimeException("User not found: " + newMemberEmail));

            String token = newMember.getFcmtoken();
            if (token == null || token.isEmpty()) {
                logger.warn("No FCM token found for user: {}", newMember.getUserEmail());
                continue;
            }

            // Customize the notification message for the new member
            String notificationTitle = "Welcome to " + group.getGroupName() + "!";
            String notificationBody = "Hi " + newMember.getUserFirstName() + "👋, you've been added to the group " + group.getGroupName() + ". Check out the latest updates!";

            try {
                // Send notification to the newly added member
                notificationService.sendNotification(notificationTitle, notificationBody, "User",
                        Set.of(token), "new_member", String.valueOf(group.getGroupId()));

                // Log the notification event
                logger.info("Notification sent to new member {} in group {}", newMember.getUserEmail(), group.getGroupName());
            } catch (Exception e) {
                logger.error("Error sending notification to new member: ", e);
            }
        }
    }



    private void notifyAdminsOfNewMembers(Group group, Set<String> oldMemberEmails) {
        Set<String> newMemberEmails = new HashSet<>(group.getMemberEmails());
        newMemberEmails.removeAll(oldMemberEmails);

        if (newMemberEmails.isEmpty()) {
            return;
        }

        for (String newMemberEmail : newMemberEmails) {
            UserInfo newMember = userInfoRepo.findByUserEmail(newMemberEmail)
                    .orElseThrow(() -> new RuntimeException("User not found: " + newMemberEmail));

            List<String> adminEmails = group.getAdminEmails();
            List<UserInfo> admins = userInfoRepo.findByUserEmailIn(adminEmails);

            for (UserInfo admin : admins) {
                String token = admin.getFcmtoken();
                if (token == null || token.isEmpty()) {
                    logger.warn("No FCM token found for admin: {}", admin.getUserEmail());
                    continue;
                }

                String notificationTitle = "Hi " + admin.getUserFirstName() + "👋";
                String notificationBody = newMember.getUserFirstName() + " " + newMember.getUserLastName() + " is now a member of your group, " + group.getGroupName() + "! Don't forget to give them a warm welcome.😊";

                try {
                    notificationService.sendNotification(notificationTitle, notificationBody, "Admin", Set.of(token), "new_member", String.valueOf(group.getGroupId()));
                } catch (Exception e) {
                    logger.error("Error sending notification: ", e);
                }
            }
        }
    }
}


