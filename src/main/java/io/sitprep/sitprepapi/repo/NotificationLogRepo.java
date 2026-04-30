package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.NotificationLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface NotificationLogRepo extends JpaRepository<NotificationLog, Long> {

    List<NotificationLog> findByRecipientEmailAndTimestampAfterOrderByTimestampAsc(
            String recipientEmail, Instant since
    );

    List<NotificationLog> findByRecipientEmailAndTypeAndTimestampAfterOrderByTimestampAsc(
            String recipientEmail, String type, Instant since
    );

    /**
     * Page of IDs older than {@code cutoff}. Used by
     * {@code RetentionSweepService} to bound each tick. Index
     * {@code idx_notif_recipient_ts} doesn't help here (not prefixed by
     * recipient), so a sequential scan over {@code timestamp} is
     * expected — fine at hourly cadence on a daily-reaped table.
     */
    @Query("SELECT n.id FROM NotificationLog n WHERE n.timestamp < :cutoff ORDER BY n.timestamp ASC")
    List<Long> findIdsOlderThan(@Param("cutoff") Instant cutoff, Pageable page);

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
}
