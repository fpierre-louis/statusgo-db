package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.dto.NotificationInboxRowDto;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.notifications.NotificationInboxFilter;
import io.sitprep.sitprepapi.notifications.NotificationPresentation;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.service.NotificationAlertProjector;
import io.sitprep.sitprepapi.notifications.NotificationPresentationBuilder;
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
    private final NotificationPresentationBuilder presentations;
    private final NotificationAlertProjector alertProjector;

    public NotificationsInboxResource(NotificationInboxService inbox,
                                      NotificationPresentationBuilder presentations,
                                      NotificationAlertProjector alertProjector) {
        this.inbox = inbox;
        this.presentations = presentations;
        this.alertProjector = alertProjector;
    }

    /**
     * Inbox page for the authenticated user, ordered timestamp DESC
     * (newest first). Both bounds optional — pass {@code before=}
     * the oldest currently-loaded row's timestamp to load the next
     * page; pass {@code since=} the newest currently-loaded row's
     * timestamp to fetch arrivals since the last refresh.
     */
    // Reads wrapped in {@link ApiResponse} per P2-3 (audit BE-02 / BE-15).
    // FE axios interceptor unwraps response.data transparently so existing
    // callers see the same payloads as before.
    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationInboxRowDto>>> page(
            @RequestParam(value = "since", required = false) String sinceStr,
            @RequestParam(value = "before", required = false) String beforeStr,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "filter", required = false) String filterStr,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        String email = AuthUtils.requireAuthenticatedEmail();
        NotificationInboxFilter filter = NotificationInboxFilter.parse(filterStr);
        if (filter == null) return ResponseEntity.badRequest().build();
        Instant since = parseInstantOrNull(sinceStr);
        Instant before = parseInstantOrNull(beforeStr);
        List<NotificationLog> page = inbox.page(email, filter, since, before, beforeId, limit);
        return ResponseEntity.ok(ApiResponse.ok(rowsOf(page), ApiMeta.now()));
    }

    /**
     * S2 C5: {@code { all, unread, alerts, alertsUnread, needsResponse }} in one
     * statement. {@code /unreadCount} is unchanged.
     */
    @GetMapping("/counts")
    public ResponseEntity<ApiResponse<NotificationLogRepo.InboxCounts>> counts() {
        String email = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(inbox.counts(email), ApiMeta.now()));
    }

    /**
     * S2 C5: every row of one alert thread for the caller, newest first — the
     * group a page shows as one item, across page boundaries.
     */
    @GetMapping("/threads/{threadKey:.+}")
    public ResponseEntity<ApiResponse<List<NotificationInboxRowDto>>> thread(@PathVariable String threadKey) {
        String email = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(rowsOf(inbox.thread(email, threadKey)), ApiMeta.now()));
    }

    /**
     * R15 read: {@code { threadKey, revisionRefs: [...shown], cutoff }}, the
     * cutoff being the record response's {@code meta.serverTime}. Marks only
     * the caller's rows in that thread whose revision was shown and that
     * arrived by the cutoff; returns {@code { updated, count }}.
     */
    @PostMapping("/alert-read")
    public ResponseEntity<Map<String, Object>> alertRead(@RequestBody(required = false) Map<String, Object> body) {
        String email = AuthUtils.requireAuthenticatedEmail();
        String threadKey = body == null || body.get("threadKey") == null ? null : String.valueOf(body.get("threadKey"));
        Instant cutoff = body == null || body.get("cutoff") == null ? null
                : parseInstantOrNull(String.valueOf(body.get("cutoff")));
        List<String> refs = new java.util.ArrayList<>();
        if (body != null && body.get("revisionRefs") instanceof java.util.Collection<?> c) {
            for (Object o : c) if (o != null) refs.add(String.valueOf(o));
        }
        if (threadKey == null || threadKey.isBlank() || cutoff == null || refs.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        int updated = inbox.markAlertThreadRead(email, threadKey, refs, cutoff);
        return ResponseEntity.ok(Map.of("updated", updated, "count", inbox.unreadCount(email)));
    }

    /** Every row ships a presentation (stored, or built in two batched lookups) and, for an official alert, {@code alert}. */
    private List<NotificationInboxRowDto> rowsOf(List<NotificationLog> page) {
        Map<Long, NotificationPresentation> byId = presentations.forRows(page);
        Map<Long, NotificationInboxRowDto.AlertRow> alerts = alertProjector.forRows(page);
        return page.stream()
                .map(r -> NotificationInboxRowDto.from(r, byId.get(r.getId()), alerts.get(r.getId())))
                .toList();
    }

    /**
     * Count of unread + non-archived rows for the authenticated user.
     * Powers the FooterNav badge. Constant-time per user via the
     * {@code idx_notif_recipient_unread} index.
     */
    @GetMapping("/unreadCount")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount() {
        String email = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("count", inbox.unreadCount(email)), ApiMeta.now()));
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
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String email = AuthUtils.requireAuthenticatedEmail();
        // S2 C5: { "newestId": <the newest row id the client saw> } — the
        // server resolves it to that row's own timestamp. The old
        // { "before": "<ISO-8601>" } body keeps working.
        Object newest = body == null ? null : body.get("newestId");
        int updated;
        if (newest != null) {
            Long id = parseLongOrNull(String.valueOf(newest));
            updated = id == null ? -1 : inbox.markAllReadThrough(email, id);
            if (updated < 0) return ResponseEntity.notFound().build();
        } else {
            Instant before = body == null || body.get("before") == null ? null
                    : parseInstantOrNull(String.valueOf(body.get("before")));
            updated = inbox.markAllReadBefore(email, before);
        }
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

    private static Long parseLongOrNull(String s) {
        try { return Long.valueOf(s.trim()); }
        catch (Exception ignored) { return null; }
    }

    private static Instant parseInstantOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); }
        catch (Exception ignored) { return null; }
    }
}
