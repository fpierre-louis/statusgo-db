package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public interface IdempotencyKeyRepo extends JpaRepository<IdempotencyKey, IdempotencyKey.PK> {

    /**
     * Bulk delete of rows past the TTL — audit P1-10 sweeper. Uses the
     * {@code idx_idem_created_at} index. {@code @Modifying} skips the
     * persistence context flush; that's fine because the sweep doesn't
     * touch any loaded entities.
     *
     * {@code @Transactional} lives on the repo method (not the caller) so
     * the sweeper survives Spring's self-invocation gotcha: the
     * {@code @Scheduled} method calls {@code sweepOnce()} via {@code this},
     * bypassing the proxy, so {@code @Transactional} on the service method
     * never fired. Repo methods are always invoked through the JDK
     * dynamic proxy, so the tx attribute reliably applies here.
     */
    @Transactional
    @Modifying
    @Query("delete from IdempotencyKey k where k.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Claim a key before the handler runs: an in-progress row (status 0, empty
     * body). The primary key makes this atomic, so of two concurrent first
     * attempts exactly one gets 1 back; the other gets 0 and must not run the
     * handler (build-10 backlog: check-then-insert let both create).
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO idempotency_keys "
            + "(caller_email, endpoint, idem_key, response_status_code, response_body, created_at) "
            + "VALUES (:caller, :endpoint, :key, 0, '', :now) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int reserve(@Param("caller") String caller, @Param("endpoint") String endpoint,
                @Param("key") String key, @Param("now") Instant now);

    /** Fill a claimed key with the handler's 2xx answer. */
    @Transactional
    @Modifying
    @Query("update IdempotencyKey k set k.responseStatusCode = :status, k.responseBody = :body "
            + "where k.callerEmail = :caller and k.endpoint = :endpoint and k.key = :key "
            + "and k.responseStatusCode = 0")
    int complete(@Param("caller") String caller, @Param("endpoint") String endpoint,
                 @Param("key") String key, @Param("status") int status, @Param("body") String body);

    /** Drop a claim whose handler failed or answered non-2xx, so a retry runs again. */
    @Transactional
    @Modifying
    @Query("delete from IdempotencyKey k where k.callerEmail = :caller and k.endpoint = :endpoint "
            + "and k.key = :key and k.responseStatusCode = 0")
    int release(@Param("caller") String caller, @Param("endpoint") String endpoint,
                @Param("key") String key);

    /**
     * Remove one expired row (an answer past the TTL, or a claim whose request
     * died). Matched on its createdAt so a fresh claim made in between survives.
     */
    @Transactional
    @Modifying
    @Query("delete from IdempotencyKey k where k.callerEmail = :caller and k.endpoint = :endpoint "
            + "and k.key = :key and k.createdAt = :createdAt")
    int deleteExpired(@Param("caller") String caller, @Param("endpoint") String endpoint,
                      @Param("key") String key, @Param("createdAt") Instant createdAt);
}
