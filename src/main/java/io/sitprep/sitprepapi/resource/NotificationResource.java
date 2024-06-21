package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/notifications")
public class NotificationResource {

    @Autowired
    private NotificationService notificationService;

    @PostMapping("/send")
    public ResponseEntity<Void> sendNotification(@RequestParam String title, @RequestParam String body, @RequestBody Set<String> tokens) {
        notificationService.sendNotification(title, body, tokens);
        return ResponseEntity.ok().build();
    }
}
