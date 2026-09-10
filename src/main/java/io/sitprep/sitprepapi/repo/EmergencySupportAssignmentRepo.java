package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.EmergencySupportAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmergencySupportAssignmentRepo extends JpaRepository<EmergencySupportAssignment, Long> {
    List<EmergencySupportAssignment> findByHouseholdId(String householdId);
    Optional<EmergencySupportAssignment> findByHouseholdIdAndSubjectTypeAndSubjectIdAndRole(
            String householdId, String subjectType, String subjectId,
            EmergencySupportAssignment.Role role);
    void deleteByHouseholdIdAndSubjectTypeAndSubjectId(
            String householdId, String subjectType, String subjectId);
}
