package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AgencyAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import io.sitprep.sitprepapi.domain.AgencyAlert.DispatchStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgencyAlertRepo extends JpaRepository<AgencyAlert, Long> {
    Optional<AgencyAlert> findByDedupKey(String dedupKey);

    List<AgencyAlert> findByPublisherGroupIdOrderByCreatedAtDesc(String groupId, Pageable pageable);

    @Query("""
           SELECT a.id FROM AgencyAlert a
            WHERE a.dispatchStatus IN :statuses
              AND (a.nextAttemptAt IS NULL OR a.nextAttemptAt <= :now)
              AND a.attemptCount < :maxAttempts
            ORDER BY a.createdAt ASC
           """)
    List<Long> findDispatchableIds(@Param("statuses") Collection<DispatchStatus> statuses,
                                   @Param("now") Instant now,
                                   @Param("maxAttempts") int maxAttempts,
                                   Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE AgencyAlert a
              SET a.dispatchStatus = :sending,
                  a.startedAt = :now,
                  a.attemptCount = a.attemptCount + 1,
                  a.lastError = NULL
            WHERE a.id = :id
              AND a.dispatchStatus IN :claimable
              AND (a.nextAttemptAt IS NULL OR a.nextAttemptAt <= :now)
              AND a.attemptCount < :maxAttempts
           """)
    int claimForDispatch(@Param("id") Long id,
                         @Param("claimable") Collection<DispatchStatus> claimable,
                         @Param("sending") DispatchStatus sending,
                         @Param("now") Instant now,
                         @Param("maxAttempts") int maxAttempts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE AgencyAlert a
              SET a.dispatchStatus = :failed,
                  a.nextAttemptAt = :now,
                  a.lastError = :message
            WHERE a.dispatchStatus = :sending
              AND a.startedAt < :staleBefore
           """)
    int recoverStaleClaims(@Param("sending") DispatchStatus sending,
                           @Param("failed") DispatchStatus failed,
                           @Param("staleBefore") Instant staleBefore,
                           @Param("now") Instant now,
                           @Param("message") String message);
}
