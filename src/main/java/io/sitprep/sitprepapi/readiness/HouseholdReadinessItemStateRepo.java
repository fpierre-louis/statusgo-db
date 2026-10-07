package io.sitprep.sitprepapi.readiness;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    /**
     * Due, unhandled reminders after {@code afterId}, oldest id first — one
     * KEYSET page. The caller passes the last id it saw, so rows it deferred
     * (still due, still unstamped) never block the rows behind them; always
     * pass page 0.
     */
    @Query("SELECT r FROM HouseholdReadinessItemState r"
            + " WHERE r.state = :state AND r.remindedAt IS NULL AND r.remindAt <= :now AND r.id > :afterId"
            + " ORDER BY r.id ASC")
    List<HouseholdReadinessItemState> findDueReminders(@Param("state") ItemStateKind state,
                                                       @Param("now") Instant now,
                                                       @Param("afterId") long afterId,
                                                       Pageable page);

    /**
     * Stamp {@code reminded_at} iff the row is still the same unhandled
     * REMIND_LATER snooze ({@code remindAt} unchanged). Returns the row count:
     * 1 = this caller owns the reminder, 0 = another instance did, or the
     * member re-snoozed / changed it in between. Its own transaction, so the
     * claim commits before anything is sent.
     */
    @Modifying
    @Transactional
    @Query("UPDATE HouseholdReadinessItemState r SET r.remindedAt = :now"
            + " WHERE r.id = :id AND r.state = :state AND r.remindedAt IS NULL AND r.remindAt = :remindAt")
    int claimReminder(@Param("id") Long id,
                      @Param("state") ItemStateKind state,
                      @Param("remindAt") Instant remindAt,
                      @Param("now") Instant now);
}
