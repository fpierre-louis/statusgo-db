package io.sitprep.sitprepapi.gamification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserTokenLedgerRepo extends JpaRepository<UserTokenLedger, Long> {

    List<UserTokenLedger> findByUserEmailOrderByEarnedAtDesc(String userEmail);

    Optional<UserTokenLedger> findByUserEmailAndTokenKey(String userEmail, String tokenKey);

    /**
     * The award write. Returns 1 when this call created the row, 0 when the
     * person already held the token — the caller notifies only on 1. A native
     * {@code ON CONFLICT DO NOTHING}, not a caught constraint violation: on
     * Postgres a failed statement aborts its transaction (SYSTEM_TRAPS T-16).
     * {@code earned_at} is stamped by the database ({@code now()}), the
     * {@code HomeStockpileItemRepo} precedent. Postgres-only; mocked in tests.
     */
    @Modifying
    @Query(value = "INSERT INTO user_token_ledger "
            + "(user_email, token_key, earned_at, source_event_type, source_event_id, metadata) "
            + "VALUES (:email, :tokenKey, now(), :sourceType, :sourceId, CAST(:metadata AS jsonb)) "
            + "ON CONFLICT (user_email, token_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("email") String email,
                       @Param("tokenKey") String tokenKey,
                       @Param("sourceType") String sourceType,
                       @Param("sourceId") String sourceId,
                       @Param("metadata") String metadataJson);

    /** Marks the caller's own awards seen. The email clause is the ownership check. */
    @Modifying
    @Query(value = "UPDATE user_token_ledger SET seen_at = now() "
            + "WHERE user_email = :email AND id IN (:ids) AND seen_at IS NULL", nativeQuery = true)
    int markSeen(@Param("email") String email, @Param("ids") Collection<Long> ids);
}
