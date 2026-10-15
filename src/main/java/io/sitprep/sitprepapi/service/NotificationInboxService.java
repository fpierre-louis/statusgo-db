package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.sitprep.sitprepapi.notifications.NotificationInboxFilter;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Read-side / state-mutation operations for the notifications inbox.
 * Implements the four endpoints in {@code docs/NOTIFICATIONS_INBOX.md}
 * "Backend — new endpoints":
 *
 * <pre>
 *   GET    /api/notifications              -> page(email, since, before, limit)
 *   GET    /api/notifications/unreadCount  -> unreadCount(email)
 *   POST   /api/notifications/{id}/read    -> markRead(id, email)
 *   POST   /api/notifications/markAllRead  -> markAllRead(email, before)
 *   DELETE /api/notifications/{id}         -> archive(id, email)
 * </pre>
 *
 * <p>All read methods scope by {@code recipientEmail} as the row-level
 * auth check — the JPQL predicates enforce ownership at the DB layer
 * so a forged path id can't read or mutate someone else's row.</p>
 *
 * <p>Writes ({@link #markRead}, {@link #markAllReadBefore},
 * {@link #archive}) use {@code @Modifying} bulk updates. They return
 * the row count so the resource can choose between 200 (1+) and 404
 * (0) without an extra read.</p>
 *
 * <p>Live broadcast on read-state changes — fans out on
 * {@code /topic/notifications/{userEmail}} via
 * {@link WebSocketMessageSender#sendInboxEvent} after each successful
 * mutation. Multi-tab + multi-device clients pick up the change without
 * polling. Payload kinds: {@code read} / {@code all-read} /
 * {@code archived}. The matching {@code created} kind is fired by
 * {@code NotificationService.saveLogRow} on every Lane A/B persist.</p>
 */
@Service
public class NotificationInboxService {

    /** Hard cap on inbox-page size to prevent runaway requests. */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Sentinels for "no time bound" used when the FE doesn't pass a
     * since/before cursor. Postgres rejects bare nullable parameters
     * on the left of {@code IS NULL}, so the repo query takes
     * required Instant params and these stand in for "open ended".
     */
    private static final Instant FAR_PAST = Instant.EPOCH;
    private static final Instant FAR_FUTURE =
            Instant.parse("9999-12-31T23:59:59Z");

    private final NotificationLogRepo repo;
    private final WebSocketMessageSender ws;

    public NotificationInboxService(NotificationLogRepo repo,
                                    WebSocketMessageSender ws) {
        this.repo = repo;
        this.ws = ws;
    }

    public List<NotificationLog> page(String email, Instant since, Instant before, Integer limit) {
        int size = (limit == null || limit <= 0) ? 50 : Math.min(limit, MAX_PAGE_SIZE);
        Instant sinceBound  = (since  != null) ? since  : FAR_PAST;
        Instant beforeBound = (before != null) ? before : FAR_FUTURE;
        return repo.findInboxPage(email, sinceBound, beforeBound, PageRequest.of(0, size));
    }

    public long unreadCount(String email) {
        return repo.countUnreadForUser(email);
    }

    @Transactional
    public boolean markRead(Long id, String email) {
        Instant readAt = Instant.now();
        boolean updated = repo.markReadByIdForUser(id, email, readAt) > 0;
        if (updated) {
            ws.sendInboxEvent(email, Map.of(
                    "kind", "read",
                    "id", id,
                    "readAt", readAt
            ));
        }
        return updated;
    }

    /**
     * Bulk read — anything older than {@code before} that's still
     * unread becomes read. Returns the number of rows updated so
     * callers can render "marked N as read" feedback. {@code before}
     * defaults to {@code Instant.now()} when null.
     */
    @Transactional
    public int markAllReadBefore(String email, Instant before) {
        Instant cutoff = (before != null) ? before : Instant.now();
        Instant readAt = Instant.now();
        int updated = repo.markAllReadBefore(email, cutoff, readAt);
        if (updated > 0) {
            ws.sendInboxEvent(email, Map.of(
                    "kind", "all-read",
                    "before", cutoff,
                    "readAt", readAt
            ));
        }
        return updated;
    }

    // ── S2 C5: server filters, counts, alert threads, cutoff reads ──

    /** Most rows one alert thread returns. A thread is one hazard's updates — tens, not thousands. */
    static final int MAX_THREAD_ROWS = 200;

    /**
     * One page under a server filter, keyset {@code (timestamp, id)}: pass the
     * last row's timestamp as {@code before} and its id as {@code beforeId}
     * and a row sharing that millisecond is no longer skipped.
     */
    public List<NotificationLog> page(String email, NotificationInboxFilter filter,
                                      Instant since, Instant before, Long beforeId, Integer limit) {
        int size = (limit == null || limit <= 0) ? 50 : Math.min(limit, MAX_PAGE_SIZE);
        return repo.findInboxPage(email, filter == null ? NotificationInboxFilter.ALL : filter,
                since, before, beforeId, size);
    }

    public NotificationLogRepo.InboxCounts counts(String email) {
        return repo.countInbox(email);
    }

    /** Every row of one alert thread for this recipient, newest first. */
    public List<NotificationLog> thread(String email, String threadKey) {
        return repo.findThreadRows(email, threadKey, MAX_THREAD_ROWS);
    }

    /**
     * R15: read the revisions the reader was SHOWN, up to the server time the
     * record response gave them. A revision that arrived after it, or one the
     * screen never rendered, stays unread. The cutoff can't be later than now.
     *
     * @return rows marked read
     */
    @Transactional
    public int markAlertThreadRead(String email, String threadKey, Collection<String> revisionRefs,
                                   Instant cutoff) {
        if (threadKey == null || threadKey.isBlank() || revisionRefs == null || cutoff == null) return 0;
        List<String> refs = revisionRefs.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (refs.isEmpty()) return 0;
        Instant now = Instant.now();
        Instant bound = cutoff.isAfter(now) ? now : cutoff;
        int updated = repo.markAlertThreadRead(email, threadKey, refs, bound, now);
        if (updated > 0) {
            ws.sendInboxEvent(email, Map.of(
                    "kind", "thread-read",
                    "threadKey", threadKey,
                    "before", bound,
                    "readAt", now
            ));
        }
        return updated;
    }

    /**
     * Mark-all-read through the newest row the client saw, by that row's
     * SERVER timestamp — the device clock never decides. A row that arrived
     * after it stays unread.
     *
     * @return rows marked read, or -1 when the id is not this recipient's row
     */
    @Transactional
    public int markAllReadThrough(String email, Long newestId) {
        if (newestId == null) return -1;
        NotificationLog seen = repo.findByIdForUser(newestId, email).orElse(null);
        if (seen == null || seen.getTimestamp() == null) return -1;
        Instant readAt = Instant.now();
        int updated = repo.markAllReadThrough(email, seen.getTimestamp(), seen.getId(), readAt);
        if (updated > 0) {
            ws.sendInboxEvent(email, Map.of(
                    "kind", "all-read",
                    "before", seen.getTimestamp(),
                    "throughId", seen.getId(),
                    "readAt", readAt
            ));
        }
        return updated;
    }

    @Transactional
    public boolean archive(Long id, String email) {
        boolean updated = repo.archiveByIdForUser(id, email, Instant.now()) > 0;
        if (updated) {
            ws.sendInboxEvent(email, Map.of(
                    "kind", "archived",
                    "id", id
            ));
        }
        return updated;
    }
}
