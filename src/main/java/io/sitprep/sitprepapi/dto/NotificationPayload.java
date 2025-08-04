package io.sitprep.sitprepapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPayload {
    private String recipientEmail;
    private String title;
    private String body;
    private String imageURL;
    private String type; // e.g., comment_on_post, comment_thread_reply, group_alert
    private String link;
    private String postId;
    private Instant timestamp;
}
