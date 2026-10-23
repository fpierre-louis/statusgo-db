package io.sitprep.sitprepapi.routine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HouseholdRoutineRepo extends JpaRepository<HouseholdRoutine, Long> {

    List<HouseholdRoutine> findByHouseholdId(String householdId);

    Optional<HouseholdRoutine> findByHouseholdIdAndRoutineKey(String householdId, String routineKey);

    long deleteByHouseholdIdAndRoutineKey(String householdId, String routineKey);
}
