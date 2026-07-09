package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HomeStockpileItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Per-household check-off state for the 14-Day At-Home Stockpile. A row's
 * existence == "on hand"; the toggle inserts or deletes it. Reads are keyed by
 * household (the leading column of the unique index).
 */
public interface HomeStockpileItemRepo extends JpaRepository<HomeStockpileItem, Long> {

    List<HomeStockpileItem> findByHouseholdId(String householdId);

    boolean existsByHouseholdIdAndItemKey(String householdId, String itemKey);

    void deleteByHouseholdIdAndItemKey(String householdId, String itemKey);

    /**
     * Idempotent "check" insert. {@code ON CONFLICT DO NOTHING} makes a concurrent
     * double-check from another member a clean no-op for the loser instead of a
     * unique-constraint violation — so the request never throws and never poisons
     * its transaction (a caught {@code DataIntegrityViolationException} does NOT
     * help on Postgres: the failed statement aborts the tx, so any later SQL in the
     * same request errors and the commit rolls back — see SYSTEM_TRAPS T-16). The
     * DB stamps {@code created_at}/{@code updated_at} via {@code now()} (a native
     * insert bypasses {@code @PrePersist}, which is fine here). Postgres-specific;
     * the repo is mocked in unit tests, so H2 never parses this SQL.
     */
    @Modifying
    @Query(value = "INSERT INTO home_stockpile_item (household_id, item_key, created_at, updated_at) "
            + "VALUES (:householdId, :itemKey, now(), now()) "
            + "ON CONFLICT (household_id, item_key) DO NOTHING", nativeQuery = true)
    void insertIfAbsent(@Param("householdId") String householdId, @Param("itemKey") String itemKey);
}
