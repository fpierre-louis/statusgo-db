package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.TaskAssignee;
import io.sitprep.sitprepapi.domain.TaskAssignee.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Work-order assignment membership + roles (Step 2). The sole authority for
 * "who is assigned and in what role"; see {@code DOCS_STEP2_ROLE_MODEL_DESIGN.md}.
 *
 * <p>Email is stored lower-cased by the assignment writer; the {@code IgnoreCase}
 * finders are belt-and-suspenders for callers that pass a raw email. Mutations
 * run inside the single {@code TaskAssignmentService} transaction (which also
 * write-through-maintains the {@code task.assignee_email} display mirror), so
 * this repo is never a second writer of that column.</p>
 */
public interface TaskAssigneeRepo extends JpaRepository<TaskAssignee, Long> {

    /** All assignees on a task, oldest first (drives the DTO fold + derivePrimary). */
    List<TaskAssignee> findByPostIdOrderByCreatedAtAsc(Long postId);

    /** Batch fold across a feed/list page — assignees for many tasks in one query. */
    List<TaskAssignee> findByPostIdIn(List<Long> postIds);

    /**
     * All rows of a role on a task. Returns a LIST (was Optional under the
     * one-Lead index) — since V50 a task may have MULTIPLE LEADs, so a single-row
     * finder would throw NonUniqueResultException the moment a second lead exists.
     */
    List<TaskAssignee> findByPostIdAndRole(Long postId, Role role);

    /**
     * The task's PRIMARY lead (point of contact), if one is marked (V50).
     * DB-guaranteed ≤1 by the partial-unique index {@code uk_task_assignee_one_primary}.
     */
    Optional<TaskAssignee> findByPostIdAndPrimaryTrue(Long postId);

    /** A specific person's assignment on a task (role change / remove / dedup). */
    Optional<TaskAssignee> findByPostIdAndEmailIgnoreCase(Long postId, String email);

    /** Guard helper: is this caller the task's Lead? (cancel / assign authority). */
    boolean existsByPostIdAndEmailIgnoreCaseAndRole(Long postId, String email, Role role);

    /** Guard helper: is this caller any assignee (Lead or Helper)? (progress authority). */
    boolean existsByPostIdAndEmailIgnoreCase(Long postId, String email);

    /** The "my work" assignee arm — tasks this person is assigned to (any role). */
    List<TaskAssignee> findByEmailIgnoreCase(String email);
}
