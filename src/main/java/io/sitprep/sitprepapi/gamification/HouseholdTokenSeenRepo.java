package io.sitprep.sitprepapi.gamification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface HouseholdTokenSeenRepo extends JpaRepository<HouseholdTokenSeen, HouseholdTokenSeen.Key> {

    @Query("select s.id.awardId from HouseholdTokenSeen s "
            + "where s.id.userEmail = :email and s.id.awardId in :awardIds")
    List<Long> findSeenAwardIds(@Param("email") String email, @Param("awardIds") Collection<Long> awardIds);

    /** Idempotent: seeing an award twice is a no-op. Postgres-only; mocked in tests. */
    @Modifying
    @Query(value = "INSERT INTO household_token_seen (award_id, user_email, seen_at) "
            + "VALUES (:awardId, :email, now()) "
            + "ON CONFLICT (award_id, user_email) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("awardId") Long awardId, @Param("email") String email);
}
