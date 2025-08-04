package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/notifications")
public class NotificationResource {

    @Autowired private NotificationService notificationService;
    @Autowired private GroupRepo groupRepo;

    @PostMapping("/send")
    public ResponseEntity<String> sendNotification(
            @RequestParam String title,
            @RequestParam String body,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) String iconUrl,
            @RequestParam Set<String> tokens,
            @RequestParam String notificationType,
            @RequestParam(required = false) String referenceId,
            @RequestParam(required = false) String targetUrl,
            @RequestParam(required = false) String additionalData
    ) {
        notificationService.sendNotification(
                title, body, sender, iconUrl, tokens, notificationType, referenceId, targetUrl, additionalData
        );
        return ResponseEntity.ok("Notification sent.");
    }

    @PostMapping("/group-alert")
    public ResponseEntity<String> triggerGroupAlert(
            @RequestParam String groupId,
            @RequestParam String initiatedBy
    ) {
        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found: " + groupId));

        notificationService.notifyGroupAlertChange(group, "Active", initiatedBy);
        return ResponseEntity.ok("Group alert broadcasted.");
    }
}
