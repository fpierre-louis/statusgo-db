package io.sitprep.sitprepapi.practice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScenarioRunRepo extends JpaRepository<ScenarioRun, Long> {

    Optional<ScenarioRun> findFirstByHouseholdIdAndScenarioKeyAndStatus(
            String householdId, String scenarioKey, ScenarioRun.Status status);

    Optional<ScenarioRun> findFirstByUserEmailAndScenarioKeyAndStatusAndHouseholdIdIsNull(
            String userEmail, String scenarioKey, ScenarioRun.Status status);

    List<ScenarioRun> findByHouseholdIdOrderByStartedAtDesc(String householdId);

    List<ScenarioRun> findByUserEmailAndHouseholdIdIsNullOrderByStartedAtDesc(String userEmail);
}
