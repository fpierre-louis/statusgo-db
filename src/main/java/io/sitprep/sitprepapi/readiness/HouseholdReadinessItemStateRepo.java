package io.sitprep.sitprepapi.readiness;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HouseholdReadinessItemStateRepo extends JpaRepository<HouseholdReadinessItemState, Long> {

    /** Every row for the household — both scopes, every member. */
    List<HouseholdReadinessItemState> findByHouseholdId(String householdId);

    /** The household-scoped row for one item (at most one; uk_hris_household_item). */
    Optional<HouseholdReadinessItemState> findFirstByHouseholdIdAndItemKeyAndScope(
            String householdId, String itemKey, String scope);

    /** One member's row for one item (at most one; uk_hris_user_item). */
    Optional<HouseholdReadinessItemState> findFirstByHouseholdIdAndItemKeyAndScopeAndUserEmail(
            String householdId, String itemKey, String scope, String userEmail);
}
