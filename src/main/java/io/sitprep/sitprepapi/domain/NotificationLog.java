package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String token;
    private String title;
    private String body;
    private String referenceId;
    private String targetUrl;
    private Instant timestamp;
    private boolean success;
    private String errorMessage;

    public NotificationLog() {}

    public NotificationLog(String token, String title, String body, String referenceId, String targetUrl,
                           Instant timestamp, boolean success, String errorMessage) {
        this.token = token;
        this.title = title;
        this.body = body;
        this.referenceId = referenceId;
        this.targetUrl = targetUrl;
        this.timestamp = timestamp;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    // Getters and setters
}
