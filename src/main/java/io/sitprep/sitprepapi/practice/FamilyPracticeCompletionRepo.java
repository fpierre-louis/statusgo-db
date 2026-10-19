package io.sitprep.sitprepapi.practice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FamilyPracticeCompletionRepo extends JpaRepository<FamilyPracticeCompletion, Long> {
    Optional<FamilyPracticeCompletion> findByHouseholdIdAndActivityKeyAndContentVersion(
            String householdId, String activityKey, int contentVersion);

    List<FamilyPracticeCompletion> findByHouseholdIdOrderByCompletedAtDesc(String householdId);
}
