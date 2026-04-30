package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.HouseholdEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface HouseholdEventRepo extends JpaRepository<HouseholdEvent, Long> {

    /**
     * Range query for the household feed. Both bounds are required
     * (non-null) — {@code HouseholdEventService} passes
     * {@code Instant.EPOCH} for "no lower bound" and a far-future
     * Instant for "no upper bound". Result is ascending so the
     * frontend can interleave events with chat messages by timestamp.
     *
     * <p>Postgres rejects the bare-nullable-parameter idiom
     * {@code (:since IS NULL OR ...)} with {@code SQLState 42P18} —
     * see the same fix on
     * {@link NotificationLogRepo#findInboxPage}.</p>
     */
    @Query("""
           SELECT e FROM HouseholdEvent e
            WHERE e.householdId = :householdId
              AND e.at > :since
              AND e.at < :before
            ORDER BY e.at ASC, e.id ASC
           """)
    List<HouseholdEvent> findRange(@Param("householdId") String householdId,
                                   @Param("since") Instant since,
                                   @Param("before") Instant before);

    /**
     * Page of IDs older than {@code cutoff} for retention sweeps. Sorted
     * ascending so the oldest get reaped first — matters when a backlog
     * spans many ticks, since the frontend's "last 7 days" view never
     * reads anything that close to the cutoff.
     */
    @Query("SELECT e.id FROM HouseholdEvent e WHERE e.at < :cutoff ORDER BY e.at ASC")
    List<Long> findIdsOlderThan(@Param("cutoff") Instant cutoff, Pageable page);

    @Modifying
    @Query("DELETE FROM HouseholdEvent e WHERE e.id IN :ids")
    int deleteByIdIn(@Param("ids") Collection<Long> ids);
}
