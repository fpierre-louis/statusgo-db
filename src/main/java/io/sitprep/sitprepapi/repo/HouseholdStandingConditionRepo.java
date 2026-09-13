package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HouseholdStandingCondition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HouseholdStandingConditionRepo extends JpaRepository<HouseholdStandingCondition, Long> {

    /** Everything for a household, newest first — active and cleared. */
    List<HouseholdStandingCondition> findByHouseholdIdOrderByUpdatedAtDesc(String householdId);

    /** The hot query: what is affecting this household right now. */
    List<HouseholdStandingCondition> findByHouseholdIdAndStatusOrderByUpdatedAtDesc(
            String householdId, String status);

    /** Batch form for the plan-document projection. */
    List<HouseholdStandingCondition> findByHouseholdIdInAndStatus(List<String> householdIds, String status);
}
