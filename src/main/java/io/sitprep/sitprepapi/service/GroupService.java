package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GroupService {

    @Autowired
    private GroupRepo groupRepo;

    @Autowired
    private UserInfoRepo userInfoRepo;

    @Autowired
    private NotificationService notificationService;

    public List<Group> getGroupsByAdminEmail(String adminEmail) {
        return groupRepo.findByAdminEmail(adminEmail);
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

        Set<String> tokens = admins.stream()
                .map(UserInfo::getFcmtoken)
                .filter(token -> token != null && !token.isEmpty())
                .collect(Collectors.toSet());

        if (tokens.isEmpty()) {
            System.out.println("No FCM tokens found for group admins.");
            return;
        }

        String notificationBody = "New member " + newMember.getUserFirstName() + " " + newMember.getUserLastName() + " has joined your group " + group.getGroupName() + ".";

        try {
            notificationService.sendNotification("New Member Joined", notificationBody, "Admin", tokens);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Group createGroup(Group group) {
        return groupRepo.save(group);
    }

    public Group updateGroup(Long groupId, Group groupDetails) {
        Group group = groupRepo.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found for this id :: " + groupId));

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
        group.setUpdatedAt(groupDetails.getUpdatedAt());
        group.setZipCode(groupDetails.getZipCode());
        group.setOwnerName(groupDetails.getOwnerName());
        group.setOwnerEmail(groupDetails.getOwnerEmail());
        group.setGroupCode(groupDetails.getGroupCode());

        return groupRepo.save(group);
    }

    public void deleteGroup(Long groupId) {
        Group group = groupRepo.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));
        groupRepo.delete(group);
    }

    public List<Group> getAllGroups() {
        return groupRepo.findAll();
    }

    public Group getGroupById(Long groupId) {
        return groupRepo.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));
    }
}
