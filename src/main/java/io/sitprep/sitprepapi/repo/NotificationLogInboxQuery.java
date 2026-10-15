package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.notifications.NotificationInboxFilter;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The inbox reads S2 C5 added to {@link NotificationLogRepo}: server filters,
 * counts, the {@code (timestamp, id)} keyset, alert threads — and the two
 * thread reads dispatch's materiality rule needs. Built as native SQL in one place
 * ({@link NotificationLogInboxQueryImpl}) so the needs_response predicate the
 * page filters by and the one the count counts are the same string.
 */
public interface NotificationLogInboxQuery {

    /**
     * One inbox page, newest first, ordered {@code (timestamp DESC, id DESC)}.
     * Archived rows never appear.
     *
     * @param since    strictly newer than this, or null
     * @param before   the keyset cursor's timestamp, or null for the first page
     * @param beforeId the cursor's row id. With it, rows at exactly
     *                 {@code before} and a smaller id are included — the row
     *                 that shared the last row's timestamp is no longer skipped.
     *                 Without it, {@code before} is strict, as it always was.
     */
    List<NotificationLog> findInboxPage(String email, NotificationInboxFilter filter,
                                        Instant since, Instant before, Long beforeId, int limit);

    /** {@code GET /api/notifications/counts}, in one statement. */
    InboxCounts countInbox(String email);

    record InboxCounts(long all, long unread, long alerts, long alertsUnread, long needsResponse) {}

    /** Every unarchived row of this recipient in one alert thread, newest first, at most {@code limit}. */
    List<NotificationLog> findThreadRows(String email, String threadKey, int limit);

    /**
     * The newest hazard row ANY recipient got for this thread since
     * {@code since} — what the thread last delivered. Matches the thread key,
     * any of {@code alertKeys}, or a pre-V110 row (no alert key) whose
     * referenceId is one of {@code legacyRefs}. {@code since} bounds the scan
     * through {@code idx_notif_type_ts}.
     */
    Optional<NotificationLog> findLatestDeliveredInThread(String threadKey, Collection<String> alertKeys,
                                                          Collection<String> legacyRefs, Instant since);

    /** Distinct recipients (as stored) of this thread's hazard rows since {@code since}, same match. */
    List<String> findThreadRecipients(String threadKey, Collection<String> alertKeys,
                                      Collection<String> legacyRefs, Instant since);
}
