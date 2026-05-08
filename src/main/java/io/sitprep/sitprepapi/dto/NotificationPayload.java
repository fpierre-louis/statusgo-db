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
    /**
     * Push lane that produced this in-app banner.
     * "A" = interruptive push (also fans out via FCM offline);
     * "B" = silent inbox (logged but no FCM);
     * "C" = ephemeral (in-session only — not logged, not in inbox).
     *
     * <p>The FE skips the optimistic unread-count bump on Lane C
     * frames since they don't earn a NotificationLog row — bumping
     * for them and then reconciling on next refresh produces a brief
     * flicker on the bell badge.</p>
     *
     * <p>Null on legacy frames; treated as Lane B by the FE
     * (consistent with the inbox spec's null-lane handling).</p>
     */
    private String lane;
}
