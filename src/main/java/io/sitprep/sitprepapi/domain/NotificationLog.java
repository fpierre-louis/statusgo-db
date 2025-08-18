package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(indexes = {
        @Index(name = "idx_notif_recipient_ts", columnList = "recipientEmail,timestamp"),
        @Index(name = "idx_notif_type_ts", columnList = "type,timestamp")
})
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // who we intended this for (so we can backfill per user)
    private String recipientEmail;

    // what kind of notification, e.g. "alert", "group_status", "post_notification"
    private String type;

    // FCM token used (may be null for socket-only logs)
    private String token;

    private String title;
    private String body;

    // e.g. groupId or postId
    private String referenceId;

    // deep link the client should navigate to
    private String targetUrl;

    private Instant timestamp;

    // FCM result; true if FCM call succeeded (not relevant for socket-only logs)
    private boolean success;

    private String errorMessage;

    public NotificationLog() {}

    public NotificationLog(String recipientEmail,
                           String type,
                           String token,
                           String title,
                           String body,
                           String referenceId,
                           String targetUrl,
                           Instant timestamp,
                           boolean success,
                           String errorMessage) {
        this.recipientEmail = recipientEmail;
        this.type = type;
        this.token = token;
        this.title = title;
        this.body = body;
        this.referenceId = referenceId;
        this.targetUrl = targetUrl;
        this.timestamp = timestamp;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    // getters/setters ...
}
