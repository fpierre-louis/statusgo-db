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
     * Range query for the household feed. Both bounds are optional —
     * {@code since} / {@code before} are passed as null when the caller
     * doesn't want that side bounded. Result is ascending so the frontend
     * can interleave events with chat messages by timestamp.
     */
    @Query("""
           SELECT e FROM HouseholdEvent e
            WHERE e.householdId = :householdId
              AND (:since IS NULL OR e.at > :since)
              AND (:before IS NULL OR e.at < :before)
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
