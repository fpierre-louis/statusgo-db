package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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
 * <p>Live broadcast on read-state changes (per spec WS topic
 * {@code /topic/notifications/{userEmail}}) is deferred — initial
 * release returns the new state in the HTTP response and lets the FE
 * patch its local list. WS push for new inbox rows already exists via
 * the broader notification fan-out path; mark-read fan-out can be
 * added when multi-device sync becomes a real ask.</p>
 */
@Service
public class NotificationInboxService {

    /** Hard cap on inbox-page size to prevent runaway requests. */
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationLogRepo repo;

    public NotificationInboxService(NotificationLogRepo repo) {
        this.repo = repo;
    }

    public List<NotificationLog> page(String email, Instant since, Instant before, Integer limit) {
        int size = (limit == null || limit <= 0) ? 50 : Math.min(limit, MAX_PAGE_SIZE);
        return repo.findInboxPage(email, since, before, PageRequest.of(0, size));
    }

    public long unreadCount(String email) {
        return repo.countUnreadForUser(email);
    }

    @Transactional
    public boolean markRead(Long id, String email) {
        return repo.markReadByIdForUser(id, email, Instant.now()) > 0;
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
        return repo.markAllReadBefore(email, cutoff, Instant.now());
    }

    @Transactional
    public boolean archive(Long id, String email) {
        return repo.archiveByIdForUser(id, email, Instant.now()) > 0;
    }
}
