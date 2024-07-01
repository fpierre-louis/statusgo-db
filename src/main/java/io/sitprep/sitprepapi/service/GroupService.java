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
        Group group = groupRepo.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found for this id :: " + groupId));

        boolean alertChangedToActive = !"Active".equals(group.getAlert()) && "Active".equals(groupDetails.getAlert());
        Set<String> oldMemberEmails = new HashSet<>(group.getMemberEmails());

        logger.info("Updating group {} with new details. Alert changed to active: {}", groupId, alertChangedToActive);

        group.setAdminEmails(groupDetails.getAdminEmails());
        group.setAlert(groupDetails.getAlert());
        group.setCreatedAt(groupDetails.getCreatedAt());
        group.setDescription(groupDetails.getDescription());
        group.setGroupName(groupDetails.getGroupName());
        group.setGroupType(groupDetails.getGroupType());
        group.setLastUpdatedBy(groupDetails.getLastUpdatedBy());
        group.setMemberCount(groupDetails.getMemberCount());
        group.setMemberEmails(groupDetails.getMemberEmails());
        group.setPrivacy(groupDetails.getPrivacy());
        group.setSubGroupIDs(groupDetails.getSubGroupIDs());
        group.setUpdatedAt(LocalDateTime.now());
        group.setZipCode(groupDetails.getZipCode());
        group.setOwnerName(groupDetails.getOwnerName());
        group.setOwnerEmail(groupDetails.getOwnerEmail());
        group.setGroupCode(groupDetails.getGroupCode());

        Group updatedGroup = groupRepo.save(group);

        if (alertChangedToActive) {
            logger.info("Alert changed to active for group {}. Sending notifications...", groupId);
            notifyGroupMembers(updatedGroup);
        }

        notifyAdminsOfNewMembers(updatedGroup, oldMemberEmails);

        return updatedGroup;
    }

    private void notifyGroupMembers(Group group) {
        List<String> memberEmails = group.getMemberEmails();
        List<UserInfo> users = userInfoRepo.findByUserEmailIn(memberEmails);

        users.forEach(user -> logger.info("User: {}, FCM Token: {}", user.getUserEmail(), user.getFcmtoken()));

        Set<String> tokens = users.stream()
                .map(UserInfo::getFcmtoken)
                .filter(token -> token != null && !token.isEmpty())
                .collect(Collectors.toSet());

        if (tokens.isEmpty()) {
            logger.warn("No FCM tokens found for group members.");
            return;
        }

        logger.info("Sending notifications to tokens: {}", tokens);

        String owner = group.getOwnerName() != null ? group.getOwnerName() : "your group leader";
        String notificationBody = "Important!" + owner + " here. Checking in on the group. Click here to let me know your status.";

        logger.info("Notification Payload - Title: Important! Group check-in from {}, Body: {}, Tokens: {}", group.getGroupName(), notificationBody, tokens);

        try {
            notificationService.sendNotification("From group " + group.getGroupName(), notificationBody, tokens);
        } catch (Exception e) {
            logger.error("Error sending notification: ", e);
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

                String notificationTitle = "Hi " + admin.getUserFirstName();
                String notificationBody = "🎉 Give a warm welcome to your new member " + newMember.getUserFirstName() + " " + newMember.getUserLastName() + " who just joined " + group.getGroupName() + "!";

                try {
                    notificationService.sendNotification(notificationTitle, notificationBody, Set.of(token));
                } catch (Exception e) {
                    logger.error("Error sending notification: ", e);
                }
            }
        }
    }

    public List<Group> getGroupsByAdminEmail(String adminEmail) {
        return groupRepo.findByAdminEmailsContaining(adminEmail);
    }

    public Group createGroup(Group group) {
        return groupRepo.save(group);
    }

    public void deleteGroup(Long groupId) {
        Group group = groupRepo.findById(groupId).orElseThrow(() -> new RuntimeException("Group not found"));
        groupRepo.delete(group);
    }

    public List<Group> getAllGroups() {
        return groupRepo.findAll();
    }

    public Group getGroupById(Long groupId) {
        return groupRepo.findById(groupId).orElseThrow(() -> new RuntimeException("Group not found"));
    }

    @Transactional
    public void joinGroup(String userEmail, Long groupId) {
        Group group = groupRepo.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        group.getMemberEmails().add(userEmail);
        groupRepo.save(group);

        // Fetch new member's details
        UserInfo newMember = userInfoRepo.findByUserEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Notify admins about the new member
        notifyAdminsOfNewMember(group, newMember);
    }

    private void notifyAdminsOfNewMember(Group group, UserInfo newMember) {
        List<String> adminEmails = group.getAdminEmails();
        List<UserInfo> admins = userInfoRepo.findByUserEmailIn(adminEmails);

        for (UserInfo admin : admins) {
            String token = admin.getFcmtoken();
            if (token == null || token.isEmpty()) {
                logger.warn("No FCM token found for admin: {}", admin.getUserEmail());
                continue;
            }

            String notificationTitle = "Hi " + admin.getUserFirstName();
            String notificationBody = "🎉 A warm welcome to our new member " + newMember.getUserFirstName() + " " + newMember.getUserLastName() + " who just joined " + group.getGroupName() + "!";

            try {
                notificationService.sendNotification(notificationTitle, notificationBody, Set.of(token));
            } catch (Exception e) {
                logger.error("Error sending notification: ", e);
            }
        }
    }
}
