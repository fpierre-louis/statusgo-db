package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.domain.TaskAssignee;
import io.sitprep.sitprepapi.domain.TaskAssignee.Role;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * The single write-through writer (Step 2 + Phase 2a multi-lead). Pure Mockito —
 * verifies the service LOGIC that must hold on H2 too (which can't express the
 * partial-unique / CHECK): additive addLead (never demotes; first lead auto-primary),
 * demoteLead keeping the person on the task + clearing a stale primary, setPrimary's
 * "must be a lead" 409 + clear-then-set FLUSH ordering (so the one-primary index is
 * never transiently violated), zero-lead allowed+audited, the assignee_email mirror
 * derivation (primary ?? earliest LEAD ?? earliest any ?? null), and the same-tx
 * loud-fail audit events. The DB-level partial-unique / CHECK are validated
 * separately by the Postgres rehearsal (V50).
 */
class TaskAssignmentServiceTest {

    private static final Long TASK = 8146L;
    private static final String GROUP = "grp-1";

    private TaskAssigneeRepo assigneeRepo;
    private PostRepo taskRepo;
    private AdminAuditLogService audit;
    private TaskAssignmentService svc;

    private Post openTask() {
        Post t = new Post();
        t.setId(TASK);
        t.setKind("task");
        t.setGroupId(GROUP);
        t.setStatus(PostStatus.OPEN);
        return t;
    }

    private TaskAssignee row(String email, Role role) {
        TaskAssignee a = new TaskAssignee();
        a.setPostId(TASK);
        a.setEmail(email);
        a.setRole(role);
        return a;
    }

    /** A LEAD row with an explicit primary flag. */
    private TaskAssignee lead(String email, boolean primary) {
        TaskAssignee a = row(email, Role.LEAD);
        a.setPrimary(primary);
        return a;
    }

    @BeforeEach
    void setUp() {
        assigneeRepo = mock(TaskAssigneeRepo.class);
        taskRepo = mock(PostRepo.class);
        audit = mock(AdminAuditLogService.class);
        svc = new TaskAssignmentService(assigneeRepo, taskRepo, audit);
        when(taskRepo.findById(TASK)).thenReturn(Optional.of(openTask()));
        when(assigneeRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(assigneeRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        // Default empty collection; individual tests override as needed.
        when(assigneeRepo.findByPostIdOrderByCreatedAtAsc(TASK)).thenReturn(List.of());
        when(assigneeRepo.findByPostIdAndRole(TASK, Role.LEAD)).thenReturn(List.of());
        when(assigneeRepo.findByPostIdAndPrimaryTrue(TASK)).thenReturn(Optional.empty());
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(eq(TASK), any())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------
    // addLead — additive; first lead auto-primary
    // ------------------------------------------------------------------

    @Test
    void addLead_firstLeadOnLeadlessTask_createsLead_marksPrimary_lowercased_mirrors_audits() {
        // no existing lead → hadLead=false → the new lead becomes primary.
        when(assigneeRepo.findByPostIdAndPrimaryTrue(TASK))
                .thenReturn(Optional.of(lead("lead@x.com", true))); // for the mirror re-derive

        svc.addLead(TASK, "LEAD@x.com", "admin@x.com");

        ArgumentCaptor<TaskAssignee> saved = ArgumentCaptor.forClass(TaskAssignee.class);
        verify(assigneeRepo).saveAndFlush(saved.capture());
        assertEquals("lead@x.com", saved.getValue().getEmail(), "email lower-cased");
        assertEquals(Role.LEAD, saved.getValue().getRole());
        assertTrue(saved.getValue().isPrimary(), "lone first lead auto-marked primary");
        verify(taskRepo).updateAssigneeMirror(eq(TASK), eq("lead@x.com"), any(), any());
        verify(audit).record(eq("admin@x.com"), eq("task.assign"), eq("task"), eq("8146"), any());
        verify(audit, never()).record(any(), eq("task.lead-change"), any(), any(), any());
    }

    @Test
    void addLead_whenAnotherLeadExists_addsSecondLead_notPrimary_neverDemotesTheFirst() {
        TaskAssignee first = lead("first@x.com", true);
        when(assigneeRepo.findByPostIdAndRole(TASK, Role.LEAD)).thenReturn(List.of(first));
        when(assigneeRepo.findByPostIdAndPrimaryTrue(TASK)).thenReturn(Optional.of(first)); // mirror stays first

        svc.addLead(TASK, "second@x.com", "admin@x.com");

        ArgumentCaptor<TaskAssignee> saved = ArgumentCaptor.forClass(TaskAssignee.class);
        verify(assigneeRepo).saveAndFlush(saved.capture());
        assertEquals("second@x.com", saved.getValue().getEmail());
        assertEquals(Role.LEAD, saved.getValue().getRole());
        assertFalse(saved.getValue().isPrimary(), "second lead is NOT auto-primary");
        // the existing first lead is never re-saved (additive, no demote) + stays primary
        assertEquals(Role.LEAD, first.getRole());
        assertTrue(first.isPrimary());
        verify(taskRepo).updateAssigneeMirror(eq(TASK), eq("first@x.com"), any(), any());
    }

    @Test
    void addLead_promotesExistingHelper_toLead() {
        TaskAssignee helper = row("h@x.com", Role.HELPER);
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "h@x.com")).thenReturn(Optional.of(helper));
        when(assigneeRepo.findByPostIdAndPrimaryTrue(TASK)).thenReturn(Optional.of(lead("h@x.com", true)));

        svc.addLead(TASK, "h@x.com", "admin@x.com");

        assertEquals(Role.LEAD, helper.getRole(), "helper promoted to lead");
        assertTrue(helper.isPrimary(), "first lead → primary");
        verify(assigneeRepo).saveAndFlush(helper);
    }

    @Test
    void addLead_whenAlreadyLead_isNoOp_doesNotDisturbPrimary() {
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "lead@x.com"))
                .thenReturn(Optional.of(lead("lead@x.com", false)));

        svc.addLead(TASK, "lead@x.com", "admin@x.com");

        verify(assigneeRepo, never()).saveAndFlush(any());
        verify(assigneeRepo, never()).findByPostIdAndRole(any(), any()); // early return before the hadLead probe
        verify(audit, never()).record(any(), eq("task.assign"), any(), any(), any());
    }

    @Test
    void addLead_onClosedTask_throws_writesNothing() {
        Post done = openTask();
        done.setStatus(PostStatus.DONE);
        when(taskRepo.findById(TASK)).thenReturn(Optional.of(done));

        assertThrows(IllegalStateException.class, () -> svc.addLead(TASK, "x@x.com", "admin@x.com"));

        verify(assigneeRepo, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // demoteLead — Lead→Helper, keeps on task, clears stale primary
    // ------------------------------------------------------------------

    @Test
    void demoteLead_leadToHelper_keepsOnTask_clearsPrimary_audits() {
        TaskAssignee theLead = lead("lead@x.com", true);
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "lead@x.com")).thenReturn(Optional.of(theLead));
        // after demote the task is leaderless with one helper → mirror = earliest any
        when(assigneeRepo.findByPostIdOrderByCreatedAtAsc(TASK))
                .thenReturn(List.of(row("lead@x.com", Role.HELPER)));

        svc.demoteLead(TASK, "LEAD@x.com", "admin@x.com");

        assertEquals(Role.HELPER, theLead.getRole(), "demoted to helper (kept on task)");
        assertFalse(theLead.isPrimary(), "a helper can never be primary");
        verify(assigneeRepo).saveAndFlush(theLead);
        verify(taskRepo).updateAssigneeMirror(eq(TASK), eq("lead@x.com"), any(), any());
        verify(audit).record(eq("admin@x.com"), eq("task.lead-change"), eq("task"), eq("8146"), any());
    }

    @Test
    void demoteLead_ofOnlyLead_leavesTaskLeaderless_allowed_notBlocked_surfacedInAudit() {
        // Owner decision (a): zero-lead is allowed — surfaced/audited, never thrown.
        TaskAssignee only = lead("solo@x.com", true);
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "solo@x.com")).thenReturn(Optional.of(only));
        when(assigneeRepo.findByPostIdOrderByCreatedAtAsc(TASK)).thenReturn(List.of()); // no one left as lead

        assertDoesNotThrow(() -> svc.demoteLead(TASK, "solo@x.com", "admin@x.com"));

        assertEquals(Role.HELPER, only.getRole());
        verify(audit).record(eq("admin@x.com"), eq("task.lead-change"), eq("task"), eq("8146"), any());
    }

    @Test
    void demoteLead_whenNotALead_isNoOp() {
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "h@x.com"))
                .thenReturn(Optional.of(row("h@x.com", Role.HELPER)));

        svc.demoteLead(TASK, "h@x.com", "admin@x.com");

        verify(assigneeRepo, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // setPrimary — must be a lead (409), clear-then-set flush ordering
    // ------------------------------------------------------------------

    @Test
    void setPrimary_targetNotAssigned_throws409_writesNothing() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.setPrimary(TASK, "ghost@x.com", "admin@x.com"));
        assertEquals(409, ex.getStatusCode().value());
        verify(assigneeRepo, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void setPrimary_targetIsHelper_throws409() {
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "h@x.com"))
                .thenReturn(Optional.of(row("h@x.com", Role.HELPER)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.setPrimary(TASK, "h@x.com", "admin@x.com"));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void setPrimary_movesPrimary_clearsOldFlushedBeforeNew_audits() {
        TaskAssignee old = lead("old@x.com", true);
        TaskAssignee target = lead("new@x.com", false);
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "new@x.com")).thenReturn(Optional.of(target));
        // 1st call (find current primary) = old; 2nd call (mirror re-derive) = new.
        when(assigneeRepo.findByPostIdAndPrimaryTrue(TASK))
                .thenReturn(Optional.of(old), Optional.of(lead("new@x.com", true)));

        svc.setPrimary(TASK, "new@x.com", "admin@x.com");

        assertFalse(old.isPrimary(), "old primary cleared");
        assertTrue(target.isPrimary(), "target is the new primary");
        // ORDERING: old is cleared+flushed BEFORE the new primary is written, so the
        // one-primary partial index is never transiently violated.
        InOrder o = inOrder(assigneeRepo);
        o.verify(assigneeRepo).saveAndFlush(argThat(a -> a.getEmail().equals("old@x.com") && !a.isPrimary()));
        o.verify(assigneeRepo).saveAndFlush(argThat(a -> a.getEmail().equals("new@x.com") && a.isPrimary()));
        verify(taskRepo).updateAssigneeMirror(eq(TASK), eq("new@x.com"), any(), any());
        verify(audit).record(eq("admin@x.com"), eq("task.set-primary"), eq("task"), eq("8146"), any());
    }

    @Test
    void setPrimary_alreadyPrimary_isNoOp() {
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "p@x.com"))
                .thenReturn(Optional.of(lead("p@x.com", true)));

        svc.setPrimary(TASK, "p@x.com", "admin@x.com");

        verify(assigneeRepo, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void setPrimary_blankEmail_clearsPrimary_audits() {
        TaskAssignee cur = lead("p@x.com", true);
        // 1st call (clearPrimary lookup) = the current primary; 2nd (mirror) = none.
        when(assigneeRepo.findByPostIdAndPrimaryTrue(TASK))
                .thenReturn(Optional.of(cur), Optional.empty());
        // after clearing, the (still-LEAD) row is the earliest lead → mirror = it
        when(assigneeRepo.findByPostIdOrderByCreatedAtAsc(TASK))
                .thenReturn(List.of(lead("p@x.com", false)));

        svc.setPrimary(TASK, "  ", "admin@x.com");

        assertFalse(cur.isPrimary(), "primary cleared");
        assertEquals(Role.LEAD, cur.getRole(), "still a lead, just not the POC");
        verify(assigneeRepo).saveAndFlush(cur);
        verify(taskRepo).updateAssigneeMirror(eq(TASK), eq("p@x.com"), any(), any());
        verify(audit).record(eq("admin@x.com"), eq("task.set-primary"), eq("task"), eq("8146"), any());
    }

    // ------------------------------------------------------------------
    // addHelper — unchanged: never demotes a Lead
    // ------------------------------------------------------------------

    @Test
    void addHelper_whenAlreadyLead_isNoOp_neverDemotes() {
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "lead@x.com"))
                .thenReturn(Optional.of(lead("lead@x.com", true)));

        svc.addHelper(TASK, "lead@x.com", "admin@x.com");

        verify(assigneeRepo, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), eq("task.add-helper"), any(), any(), any());
    }

    @Test
    void addHelper_newPerson_insertsHelper_andAudits() {
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "help@x.com")).thenReturn(Optional.empty());
        when(assigneeRepo.findByPostIdAndPrimaryTrue(TASK)).thenReturn(Optional.of(lead("lead@x.com", true)));

        svc.addHelper(TASK, "HELP@x.com", "lead@x.com");

        ArgumentCaptor<TaskAssignee> saved = ArgumentCaptor.forClass(TaskAssignee.class);
        verify(assigneeRepo).saveAndFlush(saved.capture());
        assertEquals("help@x.com", saved.getValue().getEmail());
        assertEquals(Role.HELPER, saved.getValue().getRole());
        verify(audit).record(eq("lead@x.com"), eq("task.add-helper"), eq("task"), eq("8146"), any());
    }

    // ------------------------------------------------------------------
    // removeAssignee + mirror fallback (primary ?? earliest LEAD ?? earliest any)
    // ------------------------------------------------------------------

    @Test
    void removeAssignee_deletesRow_rederivesMirrorToEarliestLead_whenNoPrimary_audits() {
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "help@x.com"))
                .thenReturn(Optional.of(row("help@x.com", Role.HELPER)));
        // no starred primary; remaining rows have a LEAD → mirror falls back to it
        when(assigneeRepo.findByPostIdAndPrimaryTrue(TASK)).thenReturn(Optional.empty());
        when(assigneeRepo.findByPostIdOrderByCreatedAtAsc(TASK))
                .thenReturn(List.of(lead("lead@x.com", false), row("other@x.com", Role.HELPER)));

        svc.removeAssignee(TASK, "help@x.com", "admin@x.com");

        verify(assigneeRepo).delete(argThat(a -> a.getEmail().equals("help@x.com")));
        verify(taskRepo).updateAssigneeMirror(eq(TASK), eq("lead@x.com"), any(), any());
        verify(audit).record(eq("admin@x.com"), eq("task.unassign"), eq("task"), eq("8146"), any());
    }

    @Test
    void removeAssignee_lastPerson_mirrorGoesNull() {
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "solo@x.com"))
                .thenReturn(Optional.of(lead("solo@x.com", true)));
        when(assigneeRepo.findByPostIdAndPrimaryTrue(TASK)).thenReturn(Optional.empty());
        when(assigneeRepo.findByPostIdOrderByCreatedAtAsc(TASK)).thenReturn(List.of());

        svc.removeAssignee(TASK, "solo@x.com", "admin@x.com");

        verify(taskRepo).updateAssigneeMirror(eq(TASK), isNull(), isNull(), isNull());
    }
}
