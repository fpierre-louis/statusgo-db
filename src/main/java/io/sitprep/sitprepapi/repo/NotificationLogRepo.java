package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.NotificationLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface NotificationLogRepo extends JpaRepository<NotificationLog, Long>, NotificationLogInboxQuery {

    List<NotificationLog> findByRecipientEmailAndTimestampAfterOrderByTimestampAsc(
            String recipientEmail, Instant since
    );

    List<NotificationLog> findByRecipientEmailAndTypeAndTimestampAfterOrderByTimestampAsc(
            String recipientEmail, String type, Instant since
    );

    @Query("""
           SELECT COUNT(n) FROM NotificationLog n
            WHERE LOWER(n.recipientEmail) = LOWER(:email)
              AND n.type = :type
              AND n.referenceId = :referenceId
              AND n.timestamp >= :since
           """)
    long countByRecipientTypeReferenceSince(@Param("email") String email,
                                            @Param("type") String type,
                                            @Param("referenceId") String referenceId,
                                            @Param("since") Instant since);

    /**
     * Page of IDs older than {@code cutoff}. Used by
     * {@code RetentionSweepService} to bound each tick. Index
     * {@code idx_notif_recipient_ts} doesn't help here (not prefixed by
     * recipient), so a sequential scan over {@code timestamp} is
     * expected — fine at hourly cadence on a daily-reaped table.
     */
    @Query("SELECT n.id FROM NotificationLog n WHERE n.timestamp < :cutoff ORDER BY n.timestamp ASC")
    List<Long> findIdsOlderThan(@Param("cutoff") Instant cutoff, Pageable page);

    /**
     * Page of IDs whose {@code archivedAt} is older than {@code cutoff}.
     * Drives the short-window archived-row sweep (default 7d) — distinct
     * from the longer {@link #findIdsOlderThan} sweep that retains rows
     * by {@code timestamp}. The shorter window matters because users tap
     * "archive" as a deliberate "I'm done with this" signal: keeping
     * archived rows around longer than a week wastes storage and risks
     * stale rows reappearing if archive is undone in the FE.
     */
    @Query("""
           SELECT n.id FROM NotificationLog n
            WHERE n.archivedAt IS NOT NULL
              AND n.archivedAt < :cutoff
            ORDER BY n.archivedAt ASC
           """)
    List<Long> findArchivedOlderThan(@Param("cutoff") Instant cutoff, Pageable page);

    /**
     * Every row already written for one notification since {@code since} —
     * an agency alert's earlier attempts, read by
     * {@code AgencyAlertDispatchService} before a retry so nobody gets a
     * second row for the same alert. {@code since} (the alert's queue time)
     * lets {@code idx_notif_type_ts} bound the scan. Runs only on a retry,
     * and returns at most recipients x attempts rows.
     */
    List<NotificationLog> findByTypeAndReferenceIdAndTimestampGreaterThanEqual(String type,
                                                                              String referenceId,
                                                                              Instant since);

    @Transactional
    @Modifying
    @Query("DELETE FROM NotificationLog n WHERE n.id IN :ids")
    int deleteByIdIn(@Param("ids") Collection<Long> ids);

    // -------------------------------------------------------------------
    // Inbox reads (per docs/NOTIFICATIONS_INBOX.md)
    // -------------------------------------------------------------------

    /**
     * Inbox page for one user, paginated and time-bounded. Excludes
     * archived rows. {@code since} and {@code before} are required
     * (non-null) — caller passes {@code Instant.EPOCH} for "no lower
     * bound" and a far-future Instant for "no upper bound". Order is
     * {@code timestamp DESC} so newest sits at the top of the inbox.
     * Caller controls page size via {@link Pageable}.
     *
     * <p><b>Why no nullable params:</b> the obvious pattern
     * {@code (:since IS NULL OR n.timestamp > :since)} fails on
     * Postgres ({@code SQLState 42P18: could not determine data type
     * of parameter}) because the bare parameter on the left side of
     * {@code IS NULL} has no type binding. H2 (used in tests) infers
     * a type silently; Postgres doesn't. Using sentinels keeps the
     * SQL portable.</p>
     */
    @Query("""
           SELECT n FROM NotificationLog n
            WHERE LOWER(n.recipientEmail) = LOWER(:email)
              AND n.archivedAt IS NULL
              AND n.timestamp > :since
              AND n.timestamp < :before
            ORDER BY n.timestamp DESC
           """)
    List<NotificationLog> findInboxPage(@Param("email") String email,
                                        @Param("since") Instant since,
                                        @Param("before") Instant before,
                                        Pageable page);

    /**
     * Unread count for the FooterNav badge. Counts rows where
     * {@code readAt IS NULL} and {@code archivedAt IS NULL}. The
     * {@code idx_notif_recipient_unread} index covers this query —
     * runs in constant time per user.
     */
    @Query("""
           SELECT COUNT(n) FROM NotificationLog n
            WHERE LOWER(n.recipientEmail) = LOWER(:email)
              AND n.archivedAt IS NULL
              AND n.readAt IS NULL
           """)
    long countUnreadForUser(@Param("email") String email);

    /**
     * Mark one row read (only if it belongs to the caller — the
     * recipient predicate is the row-level auth check).
     */
    @Transactional
    @Modifying
    @Query("""
           UPDATE NotificationLog n
              SET n.readAt = :at
            WHERE n.id = :id
              AND LOWER(n.recipientEmail) = LOWER(:email)
              AND n.readAt IS NULL
           """)
    int markReadByIdForUser(@Param("id") Long id,
                            @Param("email") String email,
                            @Param("at") Instant at);

    /**
     * Bulk mark-all-read with the {@code before} cursor pattern from
     * the spec — anything older than {@code before} that's still
     * unread becomes read. Lets the user clear without losing rows
     * that arrived mid-tap.
     */
    @Transactional
    @Modifying
    @Query("""
           UPDATE NotificationLog n
              SET n.readAt = :at
            WHERE LOWER(n.recipientEmail) = LOWER(:email)
              AND n.archivedAt IS NULL
              AND n.readAt IS NULL
              AND n.timestamp < :before
           """)
    int markAllReadBefore(@Param("email") String email,
                          @Param("before") Instant before,
                          @Param("at") Instant at);

    /**
     * Mark-all-read through a row the client saw (S2 C5): everything unread up
     * to and including {@code (timestamp, id)} — by the server's own clock on
     * that row, never the device's. A row that arrived after it, even in the
     * same millisecond, has a larger id and stays unread.
     */
    @Transactional
    @Modifying
    @Query("""
           UPDATE NotificationLog n
              SET n.readAt = :at
            WHERE LOWER(n.recipientEmail) = LOWER(:email)
              AND n.archivedAt IS NULL
              AND n.readAt IS NULL
              AND (n.timestamp < :ts OR (n.timestamp = :ts AND n.id <= :id))
           """)
    int markAllReadThrough(@Param("email") String email,
                           @Param("ts") Instant ts,
                           @Param("id") Long id,
                           @Param("at") Instant at);

    /**
     * Alert thread read (R15): only this recipient's rows in the thread whose
     * revision was SHOWN, and only up to the server time the reader saw. A
     * newer arrival — a newer revision, or one after the cutoff — stays unread.
     */
    @Transactional
    @Modifying
    @Query("""
           UPDATE NotificationLog n
              SET n.readAt = :at
            WHERE LOWER(n.recipientEmail) = LOWER(:email)
              AND n.alertThreadKey = :threadKey
              AND n.alertRevisionRef IN :refs
              AND n.timestamp <= :cutoff
              AND n.archivedAt IS NULL
              AND n.readAt IS NULL
           """)
    int markAlertThreadRead(@Param("email") String email,
                            @Param("threadKey") String threadKey,
                            @Param("refs") Collection<String> refs,
                            @Param("cutoff") Instant cutoff,
                            @Param("at") Instant at);

    /** One row, only if it is this recipient's — the markAllRead cursor's lookup. */
    @Query("""
           SELECT n FROM NotificationLog n
            WHERE n.id = :id
              AND LOWER(n.recipientEmail) = LOWER(:email)
           """)
    java.util.Optional<NotificationLog> findByIdForUser(@Param("id") Long id, @Param("email") String email);

    @Transactional
    @Modifying
    @Query("""
           UPDATE NotificationLog n
              SET n.archivedAt = :at
            WHERE n.id = :id
              AND LOWER(n.recipientEmail) = LOWER(:email)
              AND n.archivedAt IS NULL
           """)
    int archiveByIdForUser(@Param("id") Long id,
                           @Param("email") String email,
                           @Param("at") Instant at);

    // ── Quiet-hours catch-up (QuietHoursCatchUpService, V101) ─────────────

    /**
     * Recipients (lower-cased) with at least one unread, unarchived row that
     * quiet hours deferred since {@code since}. Keyset-paged by email.
     * {@code idx_notif_deferred_recipient_ts} (partial, deferred_reason IS NOT
     * NULL) keeps this to the handful of marked rows.
     */
    @Query("""
           SELECT DISTINCT LOWER(n.recipientEmail) FROM NotificationLog n
            WHERE n.deferredReason = :reason
              AND n.timestamp >= :since
              AND n.readAt IS NULL
              AND n.archivedAt IS NULL
              AND LOWER(n.recipientEmail) > :after
            ORDER BY LOWER(n.recipientEmail)
           """)
    List<String> findDeferredUnreadRecipients(@Param("reason") String reason,
                                              @Param("since") Instant since,
                                              @Param("after") String after,
                                              Pageable page);

    /** Unread, unarchived rows deferred for {@code reason} in [from, to). */
    @Query("""
           SELECT COUNT(n) FROM NotificationLog n
            WHERE LOWER(n.recipientEmail) = LOWER(:email)
              AND n.deferredReason = :reason
              AND n.timestamp >= :from
              AND n.timestamp < :to
              AND n.readAt IS NULL
              AND n.archivedAt IS NULL
           """)
    long countDeferredUnread(@Param("email") String email,
                             @Param("reason") String reason,
                             @Param("from") Instant from,
                             @Param("to") Instant to);

    /** Has a row of {@code type} been written for this recipient at or after {@code since}? */
    @Query("""
           SELECT COUNT(n) > 0 FROM NotificationLog n
            WHERE LOWER(n.recipientEmail) = LOWER(:email)
              AND n.type = :type
              AND n.timestamp >= :since
           """)
    boolean existsTypeSince(@Param("email") String email,
                            @Param("type") String type,
                            @Param("since") Instant since);
}
