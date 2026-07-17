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

    /**
     * Community map (Phase 1): mutual-aid Posts — community-scope
     * ({@code groupId IS NULL}) offers/marketplace listings that are still
     * available ({@code status}) and carry a coordinate inside the viewport
     * box. The (latitude, longitude) composite index (Flyway V28) range-scans
     * the box.
     */
    @Query("""
        SELECT p FROM Post p
         WHERE p.groupId IS NULL
           AND p.status = :status
           AND p.kind IN :kinds
           AND p.latitude  BETWEEN :minLat AND :maxLat
           AND p.longitude BETWEEN :minLng AND :maxLng
        """)
    List<Post> findAidInBounds(@Param("status") PostStatus status,
                               @Param("kinds") Set<String> kinds,
                               @Param("minLat") double minLat,
                               @Param("maxLat") double maxLat,
                               @Param("minLng") double minLng,
                               @Param("maxLng") double maxLng);

    /**
     * Metered monetization — count of group-scoped work orders a group has
     * created since {@code since} (start of the current billing month). Drives
     * {@code WorkOrderQuotaService}'s per-tier monthly cap. Only {@code
     * kind="task"} rows are counted; personal (groupId null) and civic-report
     * kinds are never metered.
     */
    long countByGroupIdAndKindAndCreatedAtGreaterThanEqual(String groupId, String kind, Instant since);

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

    // Step 2: targeted write of ONLY the display-mirror columns
    // (assignee_email / assigned_by_email / assigned_at). Used by
    // TaskAssignmentService.rederiveMirror instead of a full-entity save so a
    // concurrent status / claim / completedAt change committed by another tx is
    // NOT clobbered (Post has no @Version / @DynamicUpdate). No status guard —
    // the mirror is display-only and orthogonal to lifecycle.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE Post p
              SET p.assigneeEmail   = :email,
                  p.assignedByEmail = :by,
                  p.assignedAt      = :at
            WHERE p.id = :id
           """)
    int updateAssigneeMirror(@Param("id") Long id,
                             @Param("email") String email,
                             @Param("by") String by,
                             @Param("at") Instant at);

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

    /**
     * Nightly auto-archival sweep — bulk-flip work orders that have been in the
     * DONE status past the retention window to ARCHIVED in a single set-based
     * UPDATE. Never loads the DONE set into the JVM heap (performance mandate).
     *
     * <p>Scoped to {@code kind = :kind} ("task") so the DONE status that
     * marketplace listings reuse as "sold" is never swept. Ages off
     * {@code completedAt} — the timestamp {@code transitionComplete} stamps on
     * the DONE transition, i.e. genuinely "how long it's been DONE" — and falls
     * back via {@code COALESCE} to {@code updatedAt} for any legacy DONE rows
     * predating the {@code completedAt} column so they still age out. Uses a
     * strict {@code <} so only rows STRICTLY older than the threshold archive.</p>
     *
     * <p>{@code @PreUpdate} does not fire on a bulk JPQL update, so
     * {@code updatedAt} is set explicitly to keep the row's mtime honest.</p>
     *
     * @return the number of rows archived by this pass.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
           UPDATE Post p
              SET p.status    = :archived,
                  p.updatedAt = :now
            WHERE p.kind = :kind
              AND p.status = :done
              AND COALESCE(p.completedAt, p.updatedAt) < :threshold
           """)
    int archiveStaleWorkOrders(@Param("done") PostStatus done,
                               @Param("archived") PostStatus archived,
                               @Param("kind") String kind,
                               @Param("threshold") Instant threshold,
                               @Param("now") Instant now);

    // ---------------------------------------------------------------------
    // R2 reference probes — photo-evidence GC (PostService.updateWorkPhotos).
    // Before an R2 object is freed, the service verifies no OTHER post still
    // references its key. Both probes fail toward "referenced" (skip delete).
    // ---------------------------------------------------------------------

    /** Does any other post's imageKeys collection hold this R2 key? */
    @Query("SELECT COUNT(p) FROM Post p JOIN p.imageKeys k WHERE k = :key AND p.id <> :id")
    long countOtherPostsWithImageKey(@Param("id") Long id, @Param("key") String key);

    /**
     * Does any other post's work_details bag reference this R2 key? Keys are
     * JSON-quoted in the serialized bag, so a LIKE containment probe on
     * {@code %"<key>"%} is exact for our key shape ({@code task/<uuid>.<ext>}
     * — no LIKE metacharacters survive normalizePhotoKey's prefix gate in
     * practice; a crafted key containing % or _ can only OVER-match, which
     * merely skips the delete — fail-safe). Native because JPQL cannot look
     * inside jsonb; executed only on the rare photo-remove path. NOTE: not
     * exercised by the H2 test profile — if a future H2 test hits it, the
     * CAST may need an H2-compatible variant.
     */
    @Query(value = "SELECT COUNT(*) FROM task WHERE id <> :id "
            + "AND CAST(work_details AS varchar) LIKE :pattern",
            nativeQuery = true)
    long countOtherPostsWithWorkDetailsContaining(@Param("id") Long id,
                                                  @Param("pattern") String pattern);

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

    // Reopen / restore: return a closed task to an active state. Clears the
    // claim metadata AND completedAt — clearing completedAt resets the 7-day
    // archive clock (WorkOrderArchivalService keys off COALESCE(completedAt,
    // updatedAt)); without it a reopened DONE task would carry a stale
    // completion time and re-archive on the next sweep.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE Post p
              SET p.status            = :next,
                  p.claimedByGroupId  = NULL,
                  p.claimedByEmail    = NULL,
                  p.claimedAt         = NULL,
                  p.completedAt       = NULL
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

    /**
     * Civic epic Slice 1 — every civic report tagged to one agency, newest
     * first. Range-scans the existing {@code idx_task_tagged_agency
     * (tagged_agency_group_id, civic_status)} index on its leading column (no
     * new index / migration). The service computes per-status counts + filters
     * by {@link io.sitprep.sitprepapi.constant.CivicStatus} in memory — a
     * single agency's civic-report set is small, so one indexed read is cheap.
     *
     * <p>Slice 2 (multi-agency tagging) will replace this single-column query
     * with a join over the multi-agency link table; the {@code CivicQueueDto}
     * contract is already list-shaped so that change stays behind this repo.</p>
     */
    List<Post> findByTaggedAgencyGroupIdOrderByCreatedAtDesc(String taggedAgencyGroupId);

    /** Anything the user has claimed (across groups). */
    List<Post> findByClaimedByEmailIgnoreCaseOrderByCreatedAtDesc(String claimedByEmail);

    /** Anything assigned to the user (group push flow) — backs /me/posts?role=assignee. */
    List<Post> findByAssigneeEmailIgnoreCaseOrderByCreatedAtDesc(String assigneeEmail);

    // ---------------------------------------------------------------------
    // Bundles / projects (V51). Children point at their container via
    // project_id (distinct from the repost parent_task_id). One batch finder
    // backs both the roll-up fold (group list) and the children fold (detail);
    // the two targeted @Modifying updates move a task in/out and detach a
    // deleted project's children — set-based, never loading children onto the
    // heap, and matching the mirror-update discipline elsewhere in this repo.
    // ---------------------------------------------------------------------

    /** All child tasks of the given project containers (one batched query). */
    List<Post> findByProjectIdIn(List<Long> projectIds);

    /**
     * Move a single task into a project (or out, with {@code projectId=null}).
     * Targeted single-column update so a concurrent status/claim change on the
     * row isn't clobbered (Post has no @Version). No status guard — the project
     * link is orthogonal to lifecycle.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Post p SET p.projectId = :projectId WHERE p.id = :id")
    int updateProjectId(@Param("id") Long id, @Param("projectId") Long projectId);

    /**
     * Detach every child of a project — set-based NULL-out used when a project
     * container is deleted so its children survive as standalone tasks (belt-
     * and-suspenders with the ON DELETE SET NULL FK, and the sole mechanism on
     * the H2 test profile, which builds the schema from entities without the
     * FK). Deliberately NOT clearAutomatically: the caller's managed container
     * entity must survive for the subsequent delete(), and no child rows are
     * loaded in that transaction, so there is nothing stale to evict.
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Post p SET p.projectId = NULL WHERE p.projectId = :projectId")
    int detachChildrenOfProject(@Param("projectId") Long projectId);

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
