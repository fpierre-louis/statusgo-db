package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.service.NotificationInboxService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Inbox endpoints for the redesigned notifications surface (per
 * {@code docs/NOTIFICATIONS_INBOX.md}). Sibling resource to the
 * legacy {@link NotificationResource} — that one owns the *send*
 * fan-out (FCM / socket / DM); this one owns *read* + state
 * mutation for the inbox audit log.
 *
 * <p>All endpoints scope by the authenticated user's email; the
 * service layer enforces row ownership via JPQL predicates so a
 * forged path id can't read or mutate someone else's row.</p>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationsInboxResource {

    private final NotificationInboxService inbox;

    public NotificationsInboxResource(NotificationInboxService inbox) {
        this.inbox = inbox;
    }

    /**
     * Inbox page for the authenticated user, ordered timestamp DESC
     * (newest first). Both bounds optional — pass {@code before=}
     * the oldest currently-loaded row's timestamp to load the next
     * page; pass {@code since=} the newest currently-loaded row's
     * timestamp to fetch arrivals since the last refresh.
     */
    @GetMapping
    public ResponseEntity<List<NotificationLog>> page(
            @RequestParam(value = "since", required = false) String sinceStr,
            @RequestParam(value = "before", required = false) String beforeStr,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        String email = AuthUtils.requireAuthenticatedEmail();
        Instant since = parseInstantOrNull(sinceStr);
        Instant before = parseInstantOrNull(beforeStr);
        return ResponseEntity.ok(inbox.page(email, since, before, limit));
    }

    /**
     * Count of unread + non-archived rows for the authenticated user.
     * Powers the FooterNav badge. Constant-time per user via the
     * {@code idx_notif_recipient_unread} index.
     */
    @GetMapping("/unreadCount")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        String email = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(Map.of("count", inbox.unreadCount(email)));
    }

    /**
     * Mark one row read. 200 with the unread count when the row was
     * found + flipped; 404 when the row didn't exist or didn't belong
     * to the caller (no leak — same status either way).
     */
    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Long>> markRead(@PathVariable Long id) {
        String email = AuthUtils.requireAuthenticatedEmail();
        boolean ok = inbox.markRead(id, email);
        if (!ok) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("count", inbox.unreadCount(email)));
    }

    /**
     * Bulk mark-all-read with the {@code before} cursor pattern. Body:
     * {@code { "before": "<ISO-8601>" }} (optional — defaults to now,
     * which marks everything currently unread as read).
     */
    @PostMapping("/markAllRead")
    public ResponseEntity<Map<String, Object>> markAllRead(
            @RequestBody(required = false) Map<String, String> body
    ) {
        String email = AuthUtils.requireAuthenticatedEmail();
        Instant before = (body != null) ? parseInstantOrNull(body.get("before")) : null;
        int updated = inbox.markAllReadBefore(email, before);
        return ResponseEntity.ok(Map.of(
                "updated", updated,
                "count", inbox.unreadCount(email)
        ));
    }

    /**
     * Soft-archive (sets {@code archivedAt}). Hard delete is handled
     * by the 30d retention sweep. 200 on success, 404 when the row
     * didn't exist or didn't belong to the caller.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        String email = AuthUtils.requireAuthenticatedEmail();
        boolean ok = inbox.archive(id, email);
        if (!ok) return ResponseEntity.notFound().build();
        return ResponseEntity.noContent().build();
    }

    private static Instant parseInstantOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); }
        catch (Exception ignored) { return null; }
    }
}
