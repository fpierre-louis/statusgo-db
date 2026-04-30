package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Task;
import io.sitprep.sitprepapi.domain.Task.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface TaskRepo extends JpaRepository<Task, Long> {

    /** Group-scope feed — tasks bound to a single group, newest first. */
    List<Task> findByGroupIdOrderByCreatedAtDesc(String groupId);

    /** Group-scope feed filtered by status. */
    List<Task> findByGroupIdAndStatusOrderByCreatedAtDesc(String groupId, TaskStatus status);

    /** Anything the requester posted. */
    List<Task> findByRequesterEmailIgnoreCaseOrderByCreatedAtDesc(String requesterEmail);

    /** Anything the user has claimed (across groups). */
    List<Task> findByClaimedByEmailIgnoreCaseOrderByCreatedAtDesc(String claimedByEmail);

    /**
     * Community-scope candidates: groupId IS NULL plus zip-bucket pre-filter.
     * Caller refines with Haversine in service-layer Java. The status filter
     * lets the page show OPEN-only by default.
     */
    @Query("""
        SELECT t FROM Task t
         WHERE t.groupId IS NULL
           AND t.status IN :statuses
           AND (:zipBuckets IS NULL OR t.zipBucket IN :zipBuckets)
         ORDER BY t.createdAt DESC
        """)
    List<Task> findCommunityCandidates(
            @Param("statuses") Set<TaskStatus> statuses,
            @Param("zipBuckets") Set<String> zipBuckets
    );

    /**
     * Distinct zip-buckets that have any task with coords. Used by
     * {@code AlertModeService} to find "populated cells" — the set of
     * geocells whose mode the cron should recompute on each tick.
     * Tasks are a useful signal because every community task gets a
     * Nominatim reverse-geocode at create time, and any user who's
     * posted/received help in a cell counts as "user activity here".
     */
    @Query("""
        SELECT DISTINCT t.zipBucket FROM Task t
         WHERE t.zipBucket IS NOT NULL
           AND t.latitude IS NOT NULL
           AND t.longitude IS NOT NULL
        """)
    List<String> findDistinctZipBuckets();

    /**
     * Anchor lat/lng for a zip-bucket — picks any task's coords. For
     * mode evaluation we just need a representative point inside the
     * cell to query the alert ingest snapshot's point-radius filter
     * against. Returns a single Task (caller reads getLatitude /
     * getLongitude) or empty when the bucket is unpopulated.
     */
    @Query("""
        SELECT t FROM Task t
         WHERE t.zipBucket = :zipBucket
           AND t.latitude IS NOT NULL
           AND t.longitude IS NOT NULL
         ORDER BY t.createdAt DESC
        """)
    List<Task> findAnchorTasksByZipBucket(@Param("zipBucket") String zipBucket);
}
