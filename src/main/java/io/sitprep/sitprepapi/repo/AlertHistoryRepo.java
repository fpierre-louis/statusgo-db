package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AlertHistoryRepo extends JpaRepository<AlertHistory, String> {

    /**
     * Candidate rows for a coordinate — a NARROWING, not a match.
     *
     * <p>Each disjunct mirrors one tier of
     * {@code AlertIngestService.matchTypeFor} over the same stored inputs, so
     * the result is a strict superset of what that method would accept. It has
     * to be a superset: a row this query drops is a row the real matcher never
     * gets asked about.</p>
     *
     * <ul>
     *   <li><b>Bounding box</b> — tier 1. {@code centroidLat/Lng} is the
     *       alert's first vertex, exactly the value the polygon branch
     *       distance-tests. A box circumscribing the radius circle is wider
     *       than the circle, which is the safe direction.</li>
     *   <li><b>{@code broadcast}</b> — an alert with neither geometry nor UGC
     *       matches every coordinate, so it is never excludable.</li>
     *   <li><b>Token intersection</b> — tiers 2 and 3. Zone codes when the
     *       caller knows the point's zones, state prefixes when it does
     *       not.</li>
     * </ul>
     *
     * <p>Why it matters: measured 2026-09-07, NWS issues ~1,510 distinct alerts
     * nationally per day, so a 30-day window holds ~45,000 rows. Deserializing
     * every payload to ask {@code matchTypeFor} about it is not an option; this
     * cuts the set to the few hundred that could plausibly answer yes.</p>
     */
    @Query("""
            SELECT DISTINCT h FROM AlertHistory h
            LEFT JOIN h.tokens t
            WHERE h.lastSeenAt > :since
              AND (
                    h.broadcast = TRUE
                 OR (h.centroidLat IS NOT NULL
                     AND h.centroidLat BETWEEN :latMin AND :latMax
                     AND h.centroidLng BETWEEN :lngMin AND :lngMax)
                 OR t IN :tokens
              )
            ORDER BY h.lastSeenAt DESC
            """)
    List<AlertHistory> findCandidates(@Param("since") Instant since,
                                      @Param("latMin") double latMin,
                                      @Param("latMax") double latMax,
                                      @Param("lngMin") double lngMin,
                                      @Param("lngMax") double lngMax,
                                      @Param("tokens") Collection<String> tokens,
                                      Pageable pageable);

    /**
     * When recording began — the oldest row we still hold, across all
     * locations. The recorder ingests the whole national feed, and the feed is
     * never empty, so after the first tick this is a faithful "we have been
     * watching since". It is also self-correcting: once the sweep starts
     * biting, it converges on the retention floor, which is the true answer.
     *
     * <p>The empty state needs it. "No alerts in the last 30 days" and "we
     * started recording on Tuesday" are different sentences, and only one of
     * them is true on Wednesday.</p>
     */
    @Query("SELECT MIN(h.firstSeenAt) FROM AlertHistory h")
    Instant findRecordingSince();

    @Query("SELECT h.alertId FROM AlertHistory h WHERE h.lastSeenAt < :cutoff")
    List<String> findIdsOlderThan(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying
    @Query("DELETE FROM AlertHistory h WHERE h.alertId IN :ids")
    int deleteByAlertIdIn(@Param("ids") Collection<String> ids);
}
