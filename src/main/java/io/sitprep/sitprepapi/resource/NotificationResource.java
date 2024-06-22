package io.sitprep.sitprepapi.resource;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notifications")
public class NotificationResource {

    @PostMapping("/subscribe")
    public ResponseEntity<Void> subscribe(@RequestBody String subscription) {
        // Handle subscription, store it in the database if necessary
        return ResponseEntity.ok().build();
    }
}
