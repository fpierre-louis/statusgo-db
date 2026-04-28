package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HouseholdAccompaniment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HouseholdAccompanimentRepo extends JpaRepository<HouseholdAccompaniment, Long> {

    List<HouseholdAccompaniment> findByHouseholdId(String householdId);

    Optional<HouseholdAccompaniment> findByHouseholdIdAndAccompaniedKindAndAccompaniedId(
            String householdId, String accompaniedKind, String accompaniedId);

    @Modifying
    @Query("DELETE FROM HouseholdAccompaniment a WHERE a.householdId = :hh " +
           "AND a.accompaniedKind = :kind AND a.accompaniedId = :id")
    int deleteByTarget(@Param("hh") String householdId,
                       @Param("kind") String accompaniedKind,
                       @Param("id") String accompaniedId);

    /**
     * Used when a manual member is removed — drop any accompaniment that
     * references them on either side.
     */
    @Modifying
    @Query("DELETE FROM HouseholdAccompaniment a WHERE a.householdId = :hh AND " +
           "((a.accompaniedKind = 'manual' AND a.accompaniedId = :id) OR " +
           " (a.supervisorKind = 'manual' AND a.supervisorId = :id))")
    int deleteByManualMemberId(@Param("hh") String householdId, @Param("id") String manualMemberId);
}
