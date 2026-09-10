package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.EmergencySupportProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmergencySupportProfileRepo extends JpaRepository<EmergencySupportProfile, Long> {
    List<EmergencySupportProfile> findByHouseholdId(String householdId);
    Optional<EmergencySupportProfile> findByHouseholdIdAndSubjectTypeAndSubjectId(
            String householdId, String subjectType, String subjectId);
    void deleteByHouseholdIdAndSubjectTypeAndSubjectId(
            String householdId, String subjectType, String subjectId);
}
