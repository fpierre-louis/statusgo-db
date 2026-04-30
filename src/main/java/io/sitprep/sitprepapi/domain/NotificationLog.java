package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One row per notifiable event for a user — push fan-outs, silent
 * inbox events, and Lane-A audit rows when push was suppressed by
 * quiet hours / rate caps. Read by the inbox endpoints
 * (`GET /api/notifications`) and used by the retention sweep
 * (`RetentionSweepService`, 30d) as the high-churn log table.
 *
 * <p>Schema lives next to the {@code NotificationService} writes so
 * future authors don't have to chase three files. New columns
 * ({@code readAt}, {@code lane}, {@code category}, {@code archivedAt})
 * are nullable for back-compat — pre-2026-04-29 rows have null and
 * are still visible in the inbox; the FE treats null lane as Lane B
 * (silent) and missing category as the legacy {@code type}.</p>
 *
 * <p>Spec lives in {@code docs/NOTIFICATIONS_INBOX.md}.</p>
 */
@Entity
@Getter
@Setter
@Table(indexes = {
        @Index(name = "idx_notif_recipient_ts", columnList = "recipientEmail,timestamp"),
        @Index(name = "idx_notif_type_ts", columnList = "type,timestamp"),
        @Index(name = "idx_notif_recipient_unread", columnList = "recipientEmail,read_at")
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

    /**
     * Null = unread; non-null = the moment the user marked it read
     * (either by tap-through or via mark-all-read). Set once; we
     * intentionally don't expose a "mark unread" — see
     * NOTIFICATIONS_INBOX.md state machine.
     */
    @Column(name = "read_at")
    private Instant readAt;

    /**
     * Push policy lane decided at send time per
     * PUSH_NOTIFICATION_POLICY.md three-lane model. {@code "A"} =
     * Lane A (interruptive push fired or attempted), {@code "B"} =
     * Lane B (silent inbox-only). Null on legacy rows; the FE
     * treats null as Lane B.
     */
    @Column(name = "lane", length = 16)
    private String lane;

    /**
     * Structured category tag from the 17-entry vocabulary in
     * NOTIFICATIONS_INBOX.md (nws_severe, group_alert,
     * plan_activation_received, etc.). Drives icon + color treatment
     * in the FE inbox. Null on legacy rows — backfill from
     * {@code type} planned as a one-time ops task.
     */
    @Column(name = "category", length = 32)
    private String category;

    /**
     * Set when the user soft-archives a row from the inbox. Hard
     * deletion is handled by {@code RetentionSweepService} at the
     * 30d retention cutoff regardless of archive state.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

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
}
