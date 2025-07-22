package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/notifications")
public class NotificationResource {

    @Autowired
    private NotificationService notificationService;

    @PostMapping("/subscribe")
    public ResponseEntity<Void> subscribe(@RequestBody String subscription) {
        // Handle subscription storage if needed
        return ResponseEntity.ok().build();
    }

    /**
     * 🔥 Send a notification to one or more device tokens.
     */
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
                title,
                body,
                sender,
                iconUrl,
                tokens,
                notificationType,
                referenceId,
                targetUrl,       // 👈 Include as targetUrl to support frontend redirects
                additionalData
        );

        return ResponseEntity.ok("Notification sent successfully.");
    }
}
