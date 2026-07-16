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
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The SINGLE write-through writer for work-order assignment (Step 2 —
 * DOCS_STEP2_ROLE_MODEL_DESIGN.md; Phase 2a multi-lead — V50).
 * {@code task_assignee} is the sole authority for assignment membership + roles
 * ({@code LEAD} / {@code HELPER}); {@code task.assignee_email} is a
 * NON-authoritative "primary display" mirror that ONLY this service maintains —
 * nothing reads {@code assignee_email} to make an authorization or role decision.
 *
 * <p><b>Multi-lead model (V50):</b> a task may have SEVERAL leads (any group
 * member can be Lead or Helper) and, optionally, ONE {@code primary} lead — the
 * point of contact. The DB enforces "≤1 primary per task"
 * ({@code uk_task_assignee_one_primary}) and "a primary must be a lead"
 * ({@code ck_task_assignee_primary_is_lead}); this service enforces the same
 * invariants in code (so they hold on the H2 test profile too, which can't
 * express a partial index / CHECK). Owner-locked semantics:
 * <ul>
 *   <li>{@link #addLead} never demotes another lead (additive); the FIRST lead on
 *       a task auto-becomes primary so the display mirror stays meaningful.</li>
 *   <li>{@link #demoteLead} is Lead→Helper (keeps them on the task); if they were
 *       primary, the primary simply clears — no auto-shuffle (primary is optional).</li>
 *   <li>A task may be LEADERLESS — that's surfaced/audited, never blocked
 *       (Step-2 philosophy carried forward).</li>
 * </ul>
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
     * Add a LEAD (multi-lead; V50). Additive — NEVER demotes an existing lead.
     * Promotes the person if they were a Helper; a no-op if they are already a
     * Lead (so it never touches an existing primary). If this is the task's FIRST
     * lead, it auto-becomes the {@code primary} point of contact (a lone lead is
     * trivially the POC, and it keeps the display mirror non-null).
     */
    @Transactional
    public void addLead(Long taskId, String email, String actor) {
        String lead = norm(email);
        if (lead == null) return; // nothing to add (the resource rejects blank with 400)
        Post t = assignableTask(taskId);

        Optional<TaskAssignee> existing = assigneeRepo.findByPostIdAndEmailIgnoreCase(taskId, lead);
        if (existing.isPresent() && existing.get().getRole() == Role.LEAD) {
            return; // already a Lead — no downgrade, don't disturb the primary
        }

        // First lead on the task? (checked BEFORE the upsert; the target can't be
        // a Lead here — that's the early-return above — so this counts OTHER leads.)
        boolean hadLead = !assigneeRepo.findByPostIdAndRole(taskId, Role.LEAD).isEmpty();

        TaskAssignee row = existing.orElseGet(TaskAssignee::new);
        row.setPostId(taskId);
        row.setEmail(lead);
        row.setRole(Role.LEAD);
        row.setPrimary(!hadLead); // lone first lead is the primary
        row.setAssignedBy(norm(actor));
        if (row.getAssignedAt() == null) row.setAssignedAt(Instant.now());
        assigneeRepo.saveAndFlush(row);

        rederiveMirror(taskId);
        audit.record(actor, "task.assign", "task", String.valueOf(taskId),
                "LEAD = " + lead + (row.isPrimary() ? " (primary)" : "") + grp(t));
    }

    /**
     * Demote a LEAD to HELPER (keeps them ON the task; V50). No-op if the person
     * isn't a lead. If they were the primary, the primary clears (a Helper can't
     * be primary) and is NOT auto-reassigned — primary is optional. Removing them
     * from the task entirely is {@link #removeAssignee}.
     */
    @Transactional
    public void demoteLead(Long taskId, String email, String actor) {
        String who = norm(email);
        if (who == null) return;
        Post t = assignableTask(taskId);
        Optional<TaskAssignee> found = assigneeRepo.findByPostIdAndEmailIgnoreCase(taskId, who);
        if (found.isEmpty() || found.get().getRole() != Role.LEAD) return; // nothing to demote
        TaskAssignee row = found.get();
        boolean wasPrimary = row.isPrimary();
        row.setRole(Role.HELPER);
        row.setPrimary(false); // a Helper can never be primary (CHECK parity)
        assigneeRepo.saveAndFlush(row);
        rederiveMirror(taskId);
        audit.record(actor, "task.lead-change", "task", String.valueOf(taskId),
                "LEAD -> HELPER " + who + (wasPrimary ? " (was primary; now none)" : "") + grp(t));
    }

    /**
     * Mark a lead as the task's PRIMARY point of contact (V50). A blank/null email
     * CLEARS the primary (leaves the leads, no POC). Otherwise the target must be
     * an existing LEAD (409 if not) — a Helper can never be primary. The current
     * primary (if a different row) is cleared and FLUSHED before the new one is set
     * so the partial-unique one-primary index is never transiently violated.
     */
    @Transactional
    public void setPrimary(Long taskId, String email, String actor) {
        String target = norm(email);
        Post t = assignableTask(taskId);

        if (target == null) { clearPrimary(taskId, actor, t); return; }

        TaskAssignee row = assigneeRepo.findByPostIdAndEmailIgnoreCase(taskId, target)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Primary must be an assigned lead of this task"));
        if (row.getRole() != Role.LEAD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a lead can be the primary point of contact");
        }
        if (row.isPrimary()) return; // already primary — idempotent

        // Clear the current (different) primary FIRST + flush, so we never hold two
        // is_primary=true rows at once (would trip uk_task_assignee_one_primary).
        assigneeRepo.findByPostIdAndPrimaryTrue(taskId).ifPresent(cur -> {
            if (!cur.getEmail().equalsIgnoreCase(target)) {
                cur.setPrimary(false);
                assigneeRepo.saveAndFlush(cur);
            }
        });

        row.setPrimary(true);
        assigneeRepo.saveAndFlush(row);
        rederiveMirror(taskId);
        audit.record(actor, "task.set-primary", "task", String.valueOf(taskId),
                "PRIMARY = " + target + grp(t));
    }

    /** Clear the task's primary marker (no POC), if one is set. Idempotent. */
    private void clearPrimary(Long taskId, String actor, Post t) {
        Optional<TaskAssignee> cur = assigneeRepo.findByPostIdAndPrimaryTrue(taskId);
        if (cur.isEmpty()) { rederiveMirror(taskId); return; }
        String who = cur.get().getEmail();
        cur.get().setPrimary(false);
        assigneeRepo.saveAndFlush(cur.get());
        rederiveMirror(taskId);
        audit.record(actor, "task.set-primary", "task", String.valueOf(taskId),
                "PRIMARY cleared " + who + grp(t));
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

    /**
     * Remove a specific assignee (LEAD or HELPER). No-op if not assigned. Removing
     * a primary lead clears the primary (the row is gone) with no auto-shuffle.
     */
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

    /** Clear every assignee (all leads + all helpers). Mirror → null. */
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
     * Re-derive the non-authoritative {@code assignee_email} display mirror. With
     * multiple leads (V50) the mirror follows the POC:
     * {@code primary lead ?? earliest LEAD ?? earliest HELPER ?? null}, plus the
     * assigned_by / assigned_at of that primary. Runs inside the mutation
     * transaction so the mirror can never drift from the collection. Uses a
     * targeted 3-column @Modifying UPDATE (like the legacy bulk transitions) — so
     * it does NOT bump {@code updated_at} and cannot clobber a concurrently-committed
     * status/claim change.
     */
    private void rederiveMirror(Long taskId) {
        TaskAssignee primary = assigneeRepo.findByPostIdAndPrimaryTrue(taskId).orElse(null);
        if (primary == null) {
            // No starred primary — fall back to the earliest lead, then earliest
            // of anyone (oldest-first, so the choice is deterministic).
            List<TaskAssignee> all = assigneeRepo.findByPostIdOrderByCreatedAtAsc(taskId);
            primary = all.stream().filter(a -> a.getRole() == Role.LEAD).findFirst()
                    .orElseGet(() -> all.stream().findFirst().orElse(null));
        }
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
