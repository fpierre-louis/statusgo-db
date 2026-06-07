package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface PostRepo extends JpaRepository<Post, Long> {

    // ---------------------------------------------------------------------
    // Conditional status transitions (audit DB-03, C-3).
    //
    // Each lifecycle move (claim / assign / in-progress / complete / cancel
    // / reopen) ships as a single UPDATE with a WHERE clause that pins the
    // expected current status. Two concurrent claims for the same post
    // race in the database — the second UPDATE matches zero rows and the
    // service raises IllegalStateException. Replaces the old "read,
    // check status in Java, setStatus, save" pattern that opened a TOCTOU
    // window between the read and the save.
    //
    // {@code flushAutomatically + clearAutomatically} on each @Modifying
    // forces the persistence context to be re-synced — without
    // clearAutomatically the next findById would return the pre-UPDATE
    // entity snapshot from the L1 cache and broadcast a stale DTO.
    // ---------------------------------------------------------------------

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE Post p
              SET p.status = :next,
                  p.claimedByGroupId = :claimerGroupId,
                  p.claimedByEmail   = :claimerEmail,
                  p.claimedAt        = :claimedAt
            WHERE p.id = :id
              AND p.status = :expected
              AND p.claimedByGroupId IS NULL
           """)
    int transitionClaim(@Param("id") Long id,
                        @Param("expected") PostStatus expected,
                        @Param("next") PostStatus next,
                        @Param("claimerGroupId") String claimerGroupId,
                        @Param("claimerEmail") String claimerEmail,
                        @Param("claimedAt") Instant claimedAt);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE Post p
              SET p.assigneeEmail   = :assignee,
                  p.assignedByEmail = :assignedBy,
                  p.assignedAt      = :assignedAt
            WHERE p.id = :id
              AND p.status NOT IN :forbidden
           """)
    int transitionAssign(@Param("id") Long id,
                         @Param("assignee") String assignee,
                         @Param("assignedBy") String assignedBy,
                         @Param("assignedAt") Instant assignedAt,
                         @Param("forbidden") Set<PostStatus> forbidden);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE Post p
              SET p.status = :next
            WHERE p.id = :id
              AND p.status IN :expected
           """)
    int transitionToInProgress(@Param("id") Long id,
                               @Param("expected") Set<PostStatus> expected,
                               @Param("next") PostStatus next);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE Post p
              SET p.status      = :next,
                  p.completedAt = :completedAt
            WHERE p.id = :id
              AND p.status NOT IN :forbidden
           """)
    int transitionComplete(@Param("id") Long id,
                           @Param("next") PostStatus next,
                           @Param("completedAt") Instant completedAt,
                           @Param("forbidden") Set<PostStatus> forbidden);

    // Cancel also clears the claim metadata (claimedByGroupId / claimedByEmail
    // / claimedAt). Pre-fix: cancelled tasks kept the previous claimer's
    // identity, so DTOs / analytics / notifications showed a cancelled task as
    // still claimed by group X. Mirrors transitionReopen's NULL-out below.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE Post p
              SET p.status            = :next,
                  p.claimedByGroupId  = NULL,
                  p.claimedByEmail    = NULL,
                  p.claimedAt         = NULL
            WHERE p.id = :id
              AND p.status <> :forbidden
           """)
    int transitionCancel(@Param("id") Long id,
                         @Param("next") PostStatus next,
                         @Param("forbidden") PostStatus forbidden);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE Post p
              SET p.status            = :next,
                  p.claimedByGroupId  = NULL,
                  p.claimedByEmail    = NULL,
                  p.claimedAt         = NULL
            WHERE p.id = :id
              AND p.status = :expected
           """)
    int transitionReopen(@Param("id") Long id,
                         @Param("expected") PostStatus expected,
                         @Param("next") PostStatus next);

    /** Group-scope feed — tasks bound to a single group, newest first. */
    List<Post> findByGroupIdOrderByCreatedAtDesc(String groupId);

    /**
     * Personal preparedness tasks whose due date has passed and which
     * haven't had a reminder sent yet. Drives the daily supply-reminder
     * sweep ({@code PersonalTaskReminderService}).
     *
     * <p>Filter: {@code kind="task"} + {@code groupId IS NULL} (personal
     * scope) + an OPEN status + a {@code dueAt} in the past + a null
     * {@code reminderSentAt}. The reminder-sent check is what makes the
     * reminder fire exactly once — after the sweep stamps the field, the
     * row drops out of this query forever.</p>
     *
     * <p>Ordered oldest-due-first so a backlog (sweep was down) drains
     * the most-overdue tasks first.</p>
     */
    @Query("""
           SELECT p FROM Post p
           WHERE p.kind = 'task'
             AND p.groupId IS NULL
             AND p.status = :status
             AND p.dueAt IS NOT NULL
             AND p.dueAt <= :now
             AND p.reminderSentAt IS NULL
           ORDER BY p.dueAt ASC
           """)
    List<Post> findPersonalTasksDueForReminder(@Param("status") PostStatus status,
                                               @Param("now") Instant now,
                                               Pageable page);

    /** Group-scope feed filtered by status. */
    List<Post> findByGroupIdAndStatusOrderByCreatedAtDesc(String groupId, PostStatus status);

    /** Anything the requester posted. */
    List<Post> findByRequesterEmailIgnoreCaseOrderByCreatedAtDesc(String requesterEmail);

    /** Anything the user has claimed (across groups). */
    List<Post> findByClaimedByEmailIgnoreCaseOrderByCreatedAtDesc(String claimedByEmail);

    /**
     * Community-scope candidates: groupId IS NULL plus zip-bucket pre-filter.
     * Caller refines with Haversine in service-layer Java. The status filter
     * lets the page show OPEN-only by default.
     */
    @Query("""
        SELECT t FROM Post t
         WHERE t.groupId IS NULL
           AND t.status IN :statuses
           AND (:zipBuckets IS NULL OR t.zipBucket IN :zipBuckets)
         ORDER BY t.createdAt DESC
        """)
    List<Post> findCommunityCandidates(
            @Param("statuses") Set<PostStatus> statuses,
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
        SELECT DISTINCT t.zipBucket FROM Post t
         WHERE t.zipBucket IS NOT NULL
           AND t.latitude IS NOT NULL
           AND t.longitude IS NOT NULL
        """)
    List<String> findDistinctZipBuckets();

    /**
     * Anchor lat/lng for a zip-bucket — picks any task's coords. For
     * mode evaluation we just need a representative point inside the
     * cell to query the alert ingest snapshot's point-radius filter
     * against. Returns a single Post (caller reads getLatitude /
     * getLongitude) or empty when the bucket is unpopulated.
     */
    @Query("""
        SELECT t FROM Post t
         WHERE t.zipBucket = :zipBucket
           AND t.latitude IS NOT NULL
           AND t.longitude IS NOT NULL
         ORDER BY t.createdAt DESC
        """)
    List<Post> findAnchorTasksByZipBucket(@Param("zipBucket") String zipBucket);
}
