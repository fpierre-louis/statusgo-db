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
}
