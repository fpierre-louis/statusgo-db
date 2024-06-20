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
            notifyGroupMembers(updatedGroup);
        }

        return updatedGroup;
    }

    private void notifyGroupMembers(Group group) {
        List<UserInfo> users = userInfoRepo.findByJoinedGroupIDsContaining(group.getGroupId().toString());
        Set<String> tokens = users.stream()
                .flatMap(user -> user.getFCMTokens().stream())
                .collect(Collectors.toSet());

        if (tokens.isEmpty()) {
            logger.warn("No FCM tokens found for group members.");
            return;
        }

        String owner = group.getOwnerName() != null ? group.getOwnerName() : "your group leader";
        String notificationBody = "URGENT: " + owner + " here. Checking in to make sure you are safe. Click or Tap here and let me know your status.";

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
}
