package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/notifications")
public class NotificationResource {

    private final NotificationLogRepo notificationLogRepo;

    public NotificationResource(NotificationLogRepo notificationLogRepo) {
        this.notificationLogRepo = notificationLogRepo;
    }

    /** Backfill missed notifications for the current user since a timestamp. */
    @GetMapping("/backfill")
    public ResponseEntity<List<NotificationLog>> backfillSince(
            @RequestParam String sinceIso,
            @RequestParam(required = false) String type // e.g. "alert"
    ) {
        // Backfill is strictly self — read the verified token email so a
        // signed-in user can only pull their own missed notifications.
        String email = AuthUtils.requireAuthenticatedEmail();
        Instant since = Instant.parse(sinceIso);

        List<NotificationLog> rows = (type == null || type.isBlank())
                ? notificationLogRepo.findByRecipientEmailAndTimestampAfterOrderByTimestampAsc(email, since)
                : notificationLogRepo.findByRecipientEmailAndTypeAndTimestampAfterOrderByTimestampAsc(email, type, since);

        return ResponseEntity.ok(rows);
    }
}
