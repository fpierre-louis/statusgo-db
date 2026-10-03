package io.sitprep.sitprepapi.gamification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HouseholdTokenLedgerRepo extends JpaRepository<HouseholdTokenLedger, Long> {

    List<HouseholdTokenLedger> findByHouseholdIdOrderByEarnedAtDesc(String householdId);

    Optional<HouseholdTokenLedger> findByHouseholdIdAndTokenKey(String householdId, String tokenKey);

    boolean existsByHouseholdIdAndTokenKey(String householdId, String tokenKey);

    /** The household award write; same contract as {@link UserTokenLedgerRepo#insertIfAbsent}. */
    @Modifying
    @Query(value = "INSERT INTO household_token_ledger "
            + "(household_id, token_key, earned_at, earned_by_email, source_event_type, source_event_id, metadata) "
            + "VALUES (:householdId, :tokenKey, now(), :earnedBy, :sourceType, :sourceId, CAST(:metadata AS jsonb)) "
            + "ON CONFLICT (household_id, token_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("householdId") String householdId,
                       @Param("tokenKey") String tokenKey,
                       @Param("earnedBy") String earnedByEmail,
                       @Param("sourceType") String sourceType,
                       @Param("sourceId") String sourceId,
                       @Param("metadata") String metadataJson);
}
