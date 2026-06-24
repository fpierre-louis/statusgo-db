package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.StripeWebhookEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StripeWebhookEventRepo extends JpaRepository<StripeWebhookEvent, Long> {

    Optional<StripeWebhookEvent> findByStripeEventId(String stripeEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM StripeWebhookEvent e WHERE e.stripeEventId = :eventId")
    Optional<StripeWebhookEvent> findForUpdateByStripeEventId(@Param("eventId") String stripeEventId);

    @Modifying
    @Query(value = """
            INSERT INTO stripe_webhook_event
                (stripe_event_id, event_type, live_mode, status, received_at)
            VALUES
                (:eventId, :eventType, :liveMode, :status, :receivedAt)
            ON CONFLICT (stripe_event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId,
                       @Param("eventType") String eventType,
                       @Param("liveMode") boolean liveMode,
                       @Param("status") String status,
                       @Param("receivedAt") Instant receivedAt);

    List<StripeWebhookEvent> findAllByOrderByReceivedAtDesc(Pageable pageable);

    Optional<StripeWebhookEvent> findFirstByStatusInOrderByProcessedAtDesc(Collection<String> statuses);

    Optional<StripeWebhookEvent> findFirstByStatusOrderByProcessedAtDesc(String status);

    long countByStatusAndReceivedAtAfter(String status, Instant cutoff);
}
