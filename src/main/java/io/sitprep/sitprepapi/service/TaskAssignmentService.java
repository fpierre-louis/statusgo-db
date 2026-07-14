package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.domain.TaskAssignee;
import io.sitprep.sitprepapi.domain.TaskAssignee.Role;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The SINGLE write-through writer for work-order assignment (Step 2 —
 * DOCS_STEP2_ROLE_MODEL_DESIGN.md). {@code task_assignee} is the sole authority
 * for assignment membership + roles ({@code LEAD} / {@code HELPER});
 * {@code task.assignee_email} is a NON-authoritative "primary display" mirror
 * (Lead ?? earliest Helper ?? null) that ONLY this service maintains — nothing
 * reads {@code assignee_email} to make an authorization or role decision.
 *
 * <p>Each mutation is one {@link Transactional} unit that updates the collection,
 * re-derives the mirror, and writes its {@code AdminAuditLog} row <b>in the same
 * transaction, loud-fail</b> (Decision 2): an authority change must not commit
 * without its audit row, so the audit call is deliberately NOT wrapped in
 * try/catch (unlike the best-effort status-transition audits in PostService).</p>
 *
 * <p>Does NOT broadcast — the caller ({@code PostService}) wraps each call with
 * {@code refetchAndBroadcast} (keeping the STOMP fan-out in one place and
 * avoiding a PostService↔this circular dependency).</p>
 */
@Service
public class TaskAssignmentService {

    /** Terminal states an assignment can't be applied to (parity with legacy assign). */
    private static final Set<PostStatus> CLOSED =
            EnumSet.of(PostStatus.DONE, PostStatus.CANCELLED, PostStatus.ARCHIVED);

    private final TaskAssigneeRepo assigneeRepo;
    private final PostRepo taskRepo;
    private final AdminAuditLogService audit;

    public TaskAssignmentService(TaskAssigneeRepo assigneeRepo, PostRepo taskRepo,
                                 AdminAuditLogService audit) {
        this.assigneeRepo = assigneeRepo;
        this.taskRepo = taskRepo;
        this.audit = audit;
    }

    // -----------------------------------------------------------------
    // Mutations — each one transaction; audit is same-tx loud-fail.
    // -----------------------------------------------------------------

    /**
     * Set (or replace) the task's Lead. A blank/null email clears the Lead
     * ({@link #removeLead}). If a <i>different</i> Lead exists it is DEMOTED to
     * HELPER (never removed) with a {@code task.lead-change} audit event, then
     * the target becomes LEAD (promoting an existing Helper if present). The
     * demotion is flushed BEFORE the new LEAD is written so the partial-unique
     * one-Lead index is never transiently violated.
     */
    @Transactional
    public void setLead(Long taskId, String email, String actor) {
        String lead = norm(email);
        if (lead == null) { removeLead(taskId, actor); return; }
        Post t = assignableTask(taskId);

        // Demote an existing, different Lead first (flush so ≤1-LEAD holds).
        Optional<TaskAssignee> currentLead = assigneeRepo.findByPostIdAndRole(taskId, Role.LEAD);
        currentLead.ifPresent(cur -> {
            if (!cur.getEmail().equalsIgnoreCase(lead)) {
                cur.setRole(Role.HELPER);
                assigneeRepo.saveAndFlush(cur);
                audit.record(actor, "task.lead-change", "task", String.valueOf(taskId),
                        "LEAD -> HELPER " + cur.getEmail() + " (replaced by " + lead + ")" + grp(t));
            }
        });

        // Upsert the target as LEAD (promote if they were already a Helper).
        TaskAssignee row = assigneeRepo.findByPostIdAndEmailIgnoreCase(taskId, lead)
                .orElseGet(TaskAssignee::new);
        row.setPostId(taskId);
        row.setEmail(lead);
        row.setRole(Role.LEAD);
        row.setAssignedBy(norm(actor));
        if (row.getAssignedAt() == null) row.setAssignedAt(Instant.now());
        assigneeRepo.saveAndFlush(row);

        rederiveMirror(taskId);
        audit.record(actor, "task.assign", "task", String.valueOf(taskId),
                "LEAD = " + lead + grp(t));
    }

    /** Remove the current Lead (task becomes Lead-less; Helpers, if any, remain). */
    @Transactional
    public void removeLead(Long taskId, String actor) {
        Post t = assignableTask(taskId);
        Optional<TaskAssignee> lead = assigneeRepo.findByPostIdAndRole(taskId, Role.LEAD);
        if (lead.isEmpty()) { rederiveMirror(taskId); return; } // idempotent no-op
        String who = lead.get().getEmail();
        assigneeRepo.delete(lead.get());
        assigneeRepo.flush();
        rederiveMirror(taskId);
        // Last-Lead removal is surfaced, not silently stripped.
        audit.record(actor, "task.unassign", "task", String.valueOf(taskId),
                "LEAD removed " + who + " (now Lead-less)" + grp(t));
    }

    /**
     * Add a Helper. Idempotent: if the person is already assigned (LEAD or
     * HELPER) their role is left unchanged (adding a Helper must never DEMOTE a
     * Lead). Re-derives the mirror (a Helper becomes the display primary only
     * when there's no Lead).
     */
    @Transactional
    public void addHelper(Long taskId, String email, String actor) {
        String helper = norm(email);
        if (helper == null) return;
        Post t = assignableTask(taskId);
        Optional<TaskAssignee> existing = assigneeRepo.findByPostIdAndEmailIgnoreCase(taskId, helper);
        if (existing.isPresent()) return; // already assigned (LEAD or HELPER) — no downgrade
        TaskAssignee row = new TaskAssignee();
        row.setPostId(taskId);
        row.setEmail(helper);
        row.setRole(Role.HELPER);
        row.setAssignedBy(norm(actor));
        row.setAssignedAt(Instant.now());
        assigneeRepo.saveAndFlush(row);
        rederiveMirror(taskId);
        audit.record(actor, "task.add-helper", "task", String.valueOf(taskId),
                "HELPER + " + helper + grp(t));
    }

    /** Remove a specific assignee (LEAD or HELPER). No-op if not assigned. */
    @Transactional
    public void removeAssignee(Long taskId, String email, String actor) {
        String who = norm(email);
        if (who == null) return;
        Post t = assignableTask(taskId);
        Optional<TaskAssignee> row = assigneeRepo.findByPostIdAndEmailIgnoreCase(taskId, who);
        if (row.isEmpty()) return;
        boolean wasLead = row.get().getRole() == Role.LEAD;
        assigneeRepo.delete(row.get());
        assigneeRepo.flush();
        rederiveMirror(taskId);
        audit.record(actor, "task.unassign", "task", String.valueOf(taskId),
                (wasLead ? "LEAD removed " : "HELPER removed ") + who + grp(t));
    }

    /** Clear every assignee (Lead + all Helpers). Mirror → null. */
    @Transactional
    public void clearAssignees(Long taskId, String actor) {
        Post t = assignableTask(taskId);
        var rows = assigneeRepo.findByPostIdOrderByCreatedAtAsc(taskId);
        if (rows.isEmpty()) { rederiveMirror(taskId); return; }
        assigneeRepo.deleteAll(rows);
        assigneeRepo.flush();
        rederiveMirror(taskId);
        audit.record(actor, "task.unassign", "task", String.valueOf(taskId),
                "cleared " + rows.size() + " assignee(s)" + grp(t));
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Re-derive the non-authoritative {@code assignee_email} display mirror =
     * LEAD ?? earliest HELPER ?? null, plus the assigned_by / assigned_at of that
     * primary. Runs inside the mutation transaction so the mirror can never drift
     * from the collection. Uses a targeted 3-column @Modifying UPDATE (like the
     * legacy bulk transitions) — so it does NOT bump {@code updated_at} and cannot
     * clobber a concurrently-committed status/claim change.
     */
    private void rederiveMirror(Long taskId) {
        TaskAssignee primary = assigneeRepo.findByPostIdAndRole(taskId, Role.LEAD)
                .orElseGet(() -> assigneeRepo.findByPostIdOrderByCreatedAtAsc(taskId)
                        .stream().findFirst().orElse(null));
        // Targeted 3-column UPDATE (NOT a full-entity save) so a concurrent
        // status / claim / completedAt commit by another tx is never clobbered
        // by a stale whole-row snapshot — Post has no @Version. The mirror is
        // display-only, so no status guard is needed.
        taskRepo.updateAssigneeMirror(taskId,
                primary == null ? null : primary.getEmail(),
                primary == null ? null : primary.getAssignedBy(),
                primary == null ? null : primary.getAssignedAt());
    }

    /** Load the task; reject assignment on a closed/terminal work order. */
    private Post assignableTask(Long taskId) {
        Post t = taskRepo.findById(taskId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        if (CLOSED.contains(t.getStatus())) {
            throw new IllegalStateException("Cannot change assignment on a closed task");
        }
        return t;
    }

    private static String norm(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase();
        return v.isEmpty() ? null : v;
    }

    /** Audit-summary group suffix, matching PostService.auditWorkOrder. */
    private static String grp(Post t) {
        return t.getGroupId() != null ? " group=" + t.getGroupId() : "";
    }
}
