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

        return updatedGroup;
    }

    public void notifyGroupMembers(Group group) {
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
        String notificationBody = "URGENT: " + owner + " here. Checking in to make sure you are safe. Click or Tap here and let me know your status.";

        logger.info("Notification Payload - Title: Status Now!, Body: {}, Tokens: {}", notificationBody, tokens);

        try {
            notificationService.sendNotification("Status Now!", notificationBody, tokens);
        } catch (Exception e) {
            logger.error("Error sending notification: ", e);
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
    }
}
