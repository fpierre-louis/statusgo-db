package io.sitprep.sitprepapi.practice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PracticeSkillStateRepo extends JpaRepository<PracticeSkillState, Long> {

    Optional<PracticeSkillState> findByHouseholdIdAndSkillKey(String householdId, String skillKey);

    List<PracticeSkillState> findByHouseholdId(String householdId);

    /** Clear-history: every skill state for one household. */
    @Modifying
    @Query("DELETE FROM PracticeSkillState s WHERE s.householdId = :householdId")
    int deleteAllByHousehold(@Param("householdId") String householdId);
}
