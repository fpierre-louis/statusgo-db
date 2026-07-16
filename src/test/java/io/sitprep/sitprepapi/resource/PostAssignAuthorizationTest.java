package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.TaskAssignee.Role;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.service.PostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Step-2 assignment + progress authorization boundaries.
 *
 * <p>{@code ensureCanAssignTask} (/assign, /helpers): a group Owner/Admin OR the
 * task's LEAD — a HELPER may NEVER manage assignments. {@code ensureCanProgressTask}
 * now allows ANY assignee (LEAD or HELPER) — Helpers do the work. No Spring
 * context; the resource is invoked directly with a stubbed SecurityContext.</p>
 */
class PostAssignAuthorizationTest {

    private static final Long POST_ID = 77L;
    private static final String GROUP_ID = "grp-1";
    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String LEAD = "lead@x.com";
    private static final String HELPER = "helper@x.com";
    private static final String STRANGER = "stranger@x.com";

    private PostService tasks;
    private GroupService groupService;
    private TaskAssigneeRepo assigneeRepo;
    private PostResource resource;

    @BeforeEach
    void setUp() {
        tasks = mock(PostService.class);
        groupService = mock(GroupService.class);
        assigneeRepo = mock(TaskAssigneeRepo.class);
        resource = new PostResource(tasks, groupService, assigneeRepo);
        when(tasks.findById(POST_ID)).thenReturn(Optional.of(groupTask()));
        stubGroup();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private Post groupTask() {
        Post t = new Post();
        t.setId(POST_ID);
        t.setKind("task");
        t.setGroupId(GROUP_ID);
        t.setClaimedByEmail("claimer@x.com");
        return t;
    }

    private void stubGroup() {
        Group g = mock(Group.class);
        when(g.getOwnerEmail()).thenReturn(OWNER);
        when(g.getAdminEmails()).thenReturn(List.of(ADMIN));
        when(groupService.getGroupByPublicId(GROUP_ID)).thenReturn(g);
    }

    private void asLead(String email) {
        when(assigneeRepo.existsByPostIdAndEmailIgnoreCaseAndRole(POST_ID, email, Role.LEAD)).thenReturn(true);
    }

    // ---- assign (ensureCanAssignTask): Owner/Admin/Lead allowed; Helper/stranger denied ----

    // Phase 2a: /assign now ADDS a lead (additive), so an allowed caller must
    // pass a real group-member target (ADMIN is a member here); a blank target is
    // rejected 400 (clearing-via-assign is gone — see assign_blankTarget_rejected).

    @Test
    void owner_canAssign() {
        authenticateAs(OWNER);
        assertDoesNotThrow(() -> resource.assign(POST_ID, new PostResource.AssignRequest(ADMIN)));
        verify(tasks).assign(eq(POST_ID), eq(ADMIN), eq(OWNER));
    }

    @Test
    void admin_canAssign() {
        authenticateAs(ADMIN);
        assertDoesNotThrow(() -> resource.assign(POST_ID, new PostResource.AssignRequest(ADMIN)));
        verify(tasks).assign(eq(POST_ID), eq(ADMIN), eq(ADMIN));
    }

    @Test
    void lead_canAssign() {
        asLead(LEAD);
        authenticateAs(LEAD);
        assertDoesNotThrow(() -> resource.assign(POST_ID, new PostResource.AssignRequest(ADMIN)));
        verify(tasks).assign(eq(POST_ID), eq(ADMIN), eq(LEAD));
    }

    @Test
    void assign_blankTarget_rejected400_neverCallsService() {
        // Clean cut (owner decision c): /assign is addLead now; a blank email is a
        // 400, not a "clear". The guard passes (owner), then the blank check fires.
        authenticateAs(OWNER);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> resource.assign(POST_ID, new PostResource.AssignRequest("  ")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(tasks, never()).assign(any(), any(), any());
    }

    @Test
    void helper_cannotAssign() {
        // helper is any-assignee but NOT a Lead → the Lead check is false. The guard
        // runs before the blank check, so a null body still 403s here.
        authenticateAs(HELPER);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> resource.assign(POST_ID, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(tasks, never()).assign(any(), any(), any());
    }

    @Test
    void stranger_cannotAssign() {
        authenticateAs(STRANGER);
        assertThrows(ResponseStatusException.class, () -> resource.assign(POST_ID, null));
        verify(tasks, never()).assign(any(), any(), any());
    }

    // ---- progress (ensureCanProgressTask): ANY assignee (Helper) may progress ----

    @Test
    void helper_canProgress() {
        // Step 2: Helpers do the work — any task_assignee row may progress.
        when(assigneeRepo.existsByPostIdAndEmailIgnoreCase(POST_ID, HELPER)).thenReturn(true);
        authenticateAs(HELPER);
        assertDoesNotThrow(() -> resource.markInProgress(POST_ID));
        verify(tasks).markInProgress(POST_ID);
    }

    @Test
    void claimer_canProgress() {
        authenticateAs("claimer@x.com");
        assertDoesNotThrow(() -> resource.markInProgress(POST_ID));
        verify(tasks).markInProgress(POST_ID);
    }

    @Test
    void nonAssignee_stranger_cannotProgress() {
        authenticateAs(STRANGER);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> resource.markInProgress(POST_ID));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(tasks, never()).markInProgress(any());
    }
}
