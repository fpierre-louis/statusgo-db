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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * The single write-through writer (Step 2). Pure Mockito — verifies the service
 * LOGIC: the ≤1-LEAD ordering guarantee (demote the old Lead + FLUSH before the
 * new LEAD is written, so the partial-unique one-Lead index is never transiently
 * violated), same-transaction loud-fail audit events, addHelper never demoting a
 * Lead, and the assignee_email mirror re-derivation. The DB-level partial-unique
 * / CHECK are validated separately by the Postgres rehearsal (H2 can't express
 * a partial index).
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

    @BeforeEach
    void setUp() {
        assigneeRepo = mock(TaskAssigneeRepo.class);
        taskRepo = mock(PostRepo.class);
        audit = mock(AdminAuditLogService.class);
        svc = new TaskAssignmentService(assigneeRepo, taskRepo, audit);
        when(taskRepo.findById(TASK)).thenReturn(Optional.of(openTask()));
        when(assigneeRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(assigneeRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(assigneeRepo.findByPostIdOrderByCreatedAtAsc(TASK)).thenReturn(List.of());
    }

    // ---- setLead on a Lead-less task ----

    @Test
    void setLead_leadless_createsLead_lowercased_mirrors_andAudits() {
        // 1st findByPostIdAndRole(LEAD) = demote check (none); 2nd = rederive.
        when(assigneeRepo.findByPostIdAndRole(TASK, Role.LEAD))
                .thenReturn(Optional.empty(), Optional.of(row("lead@x.com", Role.LEAD)));
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "lead@x.com"))
                .thenReturn(Optional.empty());

        svc.setLead(TASK, "LEAD@x.com", "admin@x.com");

        ArgumentCaptor<TaskAssignee> saved = ArgumentCaptor.forClass(TaskAssignee.class);
        verify(assigneeRepo).saveAndFlush(saved.capture());
        assertEquals("lead@x.com", saved.getValue().getEmail(), "email lower-cased");
        assertEquals(Role.LEAD, saved.getValue().getRole());
        // mirror re-derived from the collection
        verify(taskRepo).updateAssigneeMirror(eq(TASK), eq("lead@x.com"), any(), any());
        // same-tx audit
        verify(audit).record(eq("admin@x.com"), eq("task.assign"), eq("task"), eq("8146"), any());
        verify(audit, never()).record(any(), eq("task.lead-change"), any(), any(), any());
    }

    // ---- setLead replacing an existing Lead: demote-then-set, ordered ----

    @Test
    void setLead_replacingExistingLead_demotesOldToHelper_flushedBeforeNew_withLeadChangeAudit() {
        TaskAssignee old = row("old@x.com", Role.LEAD);
        when(assigneeRepo.findByPostIdAndRole(TASK, Role.LEAD))
                .thenReturn(Optional.of(old), Optional.of(row("new@x.com", Role.LEAD)));
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "new@x.com"))
                .thenReturn(Optional.empty());

        svc.setLead(TASK, "new@x.com", "admin@x.com");

        // old Lead demoted to HELPER
        assertEquals(Role.HELPER, old.getRole());
        // ORDERING: the demotion is flushed BEFORE the new LEAD row is written
        // (this is what keeps the partial-unique one-Lead index from tripping).
        InOrder o = inOrder(assigneeRepo);
        o.verify(assigneeRepo).saveAndFlush(argThat(a -> a.getEmail().equals("old@x.com") && a.getRole() == Role.HELPER));
        o.verify(assigneeRepo).saveAndFlush(argThat(a -> a.getEmail().equals("new@x.com") && a.getRole() == Role.LEAD));
        // authority change audited (must-capture)
        verify(audit).record(eq("admin@x.com"), eq("task.lead-change"), eq("task"), eq("8146"), any());
        verify(audit).record(eq("admin@x.com"), eq("task.assign"), eq("task"), eq("8146"), any());
    }

    // ---- addHelper never demotes a Lead ----

    @Test
    void addHelper_whenAlreadyLead_isNoOp_neverDemotes() {
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "lead@x.com"))
                .thenReturn(Optional.of(row("lead@x.com", Role.LEAD)));

        svc.addHelper(TASK, "lead@x.com", "admin@x.com");

        verify(assigneeRepo, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), eq("task.add-helper"), any(), any(), any());
    }

    @Test
    void addHelper_newPerson_insertsHelper_andAudits() {
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "help@x.com"))
                .thenReturn(Optional.empty());
        when(assigneeRepo.findByPostIdAndRole(TASK, Role.LEAD))
                .thenReturn(Optional.of(row("lead@x.com", Role.LEAD)));

        svc.addHelper(TASK, "HELP@x.com", "lead@x.com");

        ArgumentCaptor<TaskAssignee> saved = ArgumentCaptor.forClass(TaskAssignee.class);
        verify(assigneeRepo).saveAndFlush(saved.capture());
        assertEquals("help@x.com", saved.getValue().getEmail());
        assertEquals(Role.HELPER, saved.getValue().getRole());
        verify(audit).record(eq("lead@x.com"), eq("task.add-helper"), eq("task"), eq("8146"), any());
    }

    // ---- removeAssignee ----

    @Test
    void removeAssignee_deletesRow_rederivesMirror_andAudits() {
        when(assigneeRepo.findByPostIdAndEmailIgnoreCase(TASK, "help@x.com"))
                .thenReturn(Optional.of(row("help@x.com", Role.HELPER)));
        when(assigneeRepo.findByPostIdAndRole(TASK, Role.LEAD))
                .thenReturn(Optional.of(row("lead@x.com", Role.LEAD)));

        svc.removeAssignee(TASK, "help@x.com", "admin@x.com");

        verify(assigneeRepo).delete(argThat(a -> a.getEmail().equals("help@x.com")));
        // mirror still the Lead
        verify(taskRepo).updateAssigneeMirror(eq(TASK), eq("lead@x.com"), any(), any());
        verify(audit).record(eq("admin@x.com"), eq("task.unassign"), eq("task"), eq("8146"), any());
    }

    @Test
    void removeLead_lastLead_leavesLeadless_mirrorNull_surfacedInAudit() {
        when(assigneeRepo.findByPostIdAndRole(TASK, Role.LEAD))
                .thenReturn(Optional.of(row("lead@x.com", Role.LEAD)), Optional.empty());
        when(assigneeRepo.findByPostIdOrderByCreatedAtAsc(TASK)).thenReturn(List.of());

        svc.setLead(TASK, null, "admin@x.com"); // null email → removeLead

        verify(assigneeRepo).delete(argThat(a -> a.getEmail().equals("lead@x.com")));
        verify(taskRepo).updateAssigneeMirror(eq(TASK), isNull(), any(), any());
        verify(audit).record(eq("admin@x.com"), eq("task.unassign"), eq("task"), eq("8146"), any());
    }

    // ---- closed-task guard ----

    @Test
    void setLead_onClosedTask_throws_andWritesNothing() {
        Post done = openTask();
        done.setStatus(PostStatus.DONE);
        when(taskRepo.findById(TASK)).thenReturn(Optional.of(done));

        assertThrows(IllegalStateException.class, () -> svc.setLead(TASK, "x@x.com", "admin@x.com"));

        verify(assigneeRepo, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }
}
