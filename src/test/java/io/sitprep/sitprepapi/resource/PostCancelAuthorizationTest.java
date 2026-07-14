package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
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
import static org.mockito.Mockito.*;

/**
 * Cancel-authorization boundary for {@code POST /api/posts/{id}/cancel}
 * (work-order overhaul, Step 1 — cancel-broaden).
 *
 * <p>Policy under test ({@code ensureCanCancelTask}): cancel is allowed for
 * EXACTLY two parties — the <b>requester</b> (self-cancel) OR the task's-group
 * <b>Owner/Admin</b>. It is deliberately NARROWER than progress/complete
 * ({@code ensureCanProgressTask}): the <b>claimer</b> and the <b>assignee</b>
 * (who becomes a Helper in the Step-2 role model) must NEVER be able to cancel
 * a task out from under the requester. Task-Lead cancel is added in Step 2,
 * when Lead is a real, checkable role.</p>
 *
 * <p>No Spring context — the resource is invoked directly with a stubbed
 * {@link SecurityContextHolder}, mirroring {@code PlanActivationMapResourceTest}.</p>
 */
class PostCancelAuthorizationTest {

    private static final Long POST_ID = 55L;
    private static final String GROUP_ID = "grp-1";
    private static final String REQUESTER = "requester@x.com";
    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String CLAIMER = "claimer@x.com";
    private static final String ASSIGNEE = "assignee@x.com";
    private static final String STRANGER = "stranger@x.com";

    private PostService tasks;
    private GroupService groupService;
    private io.sitprep.sitprepapi.repo.TaskAssigneeRepo assigneeRepo;
    private PostResource resource;

    @BeforeEach
    void setUp() {
        tasks = mock(PostService.class);
        groupService = mock(GroupService.class);
        assigneeRepo = mock(io.sitprep.sitprepapi.repo.TaskAssigneeRepo.class);
        resource = new PostResource(tasks, groupService, assigneeRepo);
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

    /** A group work order with a distinct requester, claimer, and assignee. */
    private Post groupTask() {
        Post t = new Post();
        t.setId(POST_ID);
        t.setKind("task");
        t.setGroupId(GROUP_ID);
        t.setRequesterEmail(REQUESTER);
        t.setClaimedByEmail(CLAIMER);
        t.setAssigneeEmail(ASSIGNEE);
        t.setStatus(Post.PostStatus.IN_PROGRESS);
        return t;
    }

    /** Stub the owning group with a distinct owner + one admin. */
    private void stubGroup() {
        Group g = mock(Group.class);
        when(g.getOwnerEmail()).thenReturn(OWNER);
        when(g.getAdminEmails()).thenReturn(List.of(ADMIN));
        when(groupService.getGroupByPublicId(GROUP_ID)).thenReturn(g);
    }

    private void assertForbidden(String caller) {
        authenticateAs(caller);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> resource.cancel(POST_ID));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(tasks, never()).cancel(any());
    }

    // -----------------------------------------------------------------
    // ALLOW — requester (self-cancel) + group owner + group admin
    // -----------------------------------------------------------------

    @Test
    void requester_canSelfCancel_withoutGroupLookup() {
        when(tasks.findById(POST_ID)).thenReturn(Optional.of(groupTask()));
        authenticateAs(REQUESTER);

        assertDoesNotThrow(() -> resource.cancel(POST_ID));

        verify(tasks).cancel(POST_ID);
        // Self-cancel short-circuits before any group resolution.
        verify(groupService, never()).getGroupByPublicId(any());
    }

    @Test
    void groupOwner_canCancel() {
        when(tasks.findById(POST_ID)).thenReturn(Optional.of(groupTask()));
        stubGroup();
        authenticateAs(OWNER);

        assertDoesNotThrow(() -> resource.cancel(POST_ID));

        verify(tasks).cancel(POST_ID);
    }

    @Test
    void groupAdmin_canCancel() {
        when(tasks.findById(POST_ID)).thenReturn(Optional.of(groupTask()));
        stubGroup();
        authenticateAs(ADMIN);

        assertDoesNotThrow(() -> resource.cancel(POST_ID));

        verify(tasks).cancel(POST_ID);
    }

    @Test
    void lead_canCancel() {
        // Step 2: the task's Lead may cancel.
        when(tasks.findById(POST_ID)).thenReturn(Optional.of(groupTask()));
        when(assigneeRepo.existsByPostIdAndEmailIgnoreCaseAndRole(
                POST_ID, "lead@x.com", io.sitprep.sitprepapi.domain.TaskAssignee.Role.LEAD)).thenReturn(true);
        authenticateAs("lead@x.com");

        assertDoesNotThrow(() -> resource.cancel(POST_ID));

        verify(tasks).cancel(POST_ID);
    }

    @Test
    void helper_cannotCancel() {
        // A Helper is NOT a Lead — the assigneeRepo LEAD check returns false (default).
        when(tasks.findById(POST_ID)).thenReturn(Optional.of(groupTask()));
        stubGroup();
        assertForbidden("helper@x.com");
    }

    // -----------------------------------------------------------------
    // DENY — claimer + assignee must NOT cancel (the Step-1 correction)
    // -----------------------------------------------------------------

    @Test
    void claimer_cannotCancel() {
        when(tasks.findById(POST_ID)).thenReturn(Optional.of(groupTask()));
        stubGroup(); // claimer is neither owner nor admin of the group
        assertForbidden(CLAIMER);
    }

    @Test
    void assignee_cannotCancel() {
        // The single assignee becomes a Helper in Step 2 — Helpers never cancel.
        when(tasks.findById(POST_ID)).thenReturn(Optional.of(groupTask()));
        stubGroup();
        assertForbidden(ASSIGNEE);
    }

    @Test
    void stranger_cannotCancel() {
        when(tasks.findById(POST_ID)).thenReturn(Optional.of(groupTask()));
        stubGroup();
        assertForbidden(STRANGER);
    }

    @Test
    void adminOfDifferentGroup_cannotCancel() {
        // The group resolves, but the caller is neither owner nor admin of THIS
        // task's group — wrong-group admins get 403 (authority is group-scoped).
        when(tasks.findById(POST_ID)).thenReturn(Optional.of(groupTask()));
        stubGroup();
        assertForbidden("admin-of-other-group@x.com");
    }

    // -----------------------------------------------------------------
    // Personal task — no group, so the admin/owner broadening can't apply
    // -----------------------------------------------------------------

    @Test
    void personalTask_onlyRequesterCancels_noGroupBroadening() {
        Post personal = groupTask();
        personal.setGroupId(null); // personal / community scope — no owning group
        when(tasks.findById(POST_ID)).thenReturn(Optional.of(personal));
        // Even a would-be admin has no group to be an admin OF → 403.
        assertForbidden(OWNER);
    }

    // -----------------------------------------------------------------
    // 401 / 404
    // -----------------------------------------------------------------

    @Test
    void unauthenticated_returns401_neverLooksUpTask() {
        SecurityContextHolder.clearContext();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> resource.cancel(POST_ID));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verify(tasks, never()).findById(any());
        verify(tasks, never()).cancel(any());
    }

    @Test
    void missingTask_returns404() {
        when(tasks.findById(POST_ID)).thenReturn(Optional.empty());
        authenticateAs(REQUESTER);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> resource.cancel(POST_ID));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(tasks, never()).cancel(any());
    }
}
