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
}
