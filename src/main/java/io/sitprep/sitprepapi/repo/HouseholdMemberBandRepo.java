package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HouseholdMemberBand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HouseholdMemberBandRepo extends JpaRepository<HouseholdMemberBand, HouseholdMemberBand.Key> {

    List<HouseholdMemberBand> findByHouseholdId(String householdId);

    @Modifying
    @Query("DELETE FROM HouseholdMemberBand b WHERE b.householdId = :hh AND b.userEmail = :email")
    int deleteMembership(@Param("hh") String householdId, @Param("email") String userEmail);
}
