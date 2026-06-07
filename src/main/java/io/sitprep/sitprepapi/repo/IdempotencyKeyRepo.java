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
}
