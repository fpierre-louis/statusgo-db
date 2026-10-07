package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HouseholdClaimInvite;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface HouseholdClaimInviteRepo extends JpaRepository<HouseholdClaimInvite, String> {

    /** Unconsumed, unrevoked rows for one manual member (expired ones included). */
    @Query("SELECT i FROM HouseholdClaimInvite i WHERE i.manualMemberId = :mid "
            + "AND i.consumedAt IS NULL AND i.revokedAt IS NULL ORDER BY i.issuedAt DESC")
    List<HouseholdClaimInvite> findOpenForMember(@Param("mid") String manualMemberId);

    /** Unconsumed, unrevoked rows for a household — the composition's claim.pending. */
    @Query("SELECT i FROM HouseholdClaimInvite i WHERE i.householdId = :hh "
            + "AND i.consumedAt IS NULL AND i.revokedAt IS NULL AND i.expiresAt > :now")
    List<HouseholdClaimInvite> findLiveForHousehold(@Param("hh") String householdId, @Param("now") Instant now);

    long countByHouseholdIdAndIssuedAtAfter(String householdId, Instant since);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM HouseholdClaimInvite i WHERE i.token = :token")
    Optional<HouseholdClaimInvite> findByTokenForUpdate(@Param("token") String token);
}
