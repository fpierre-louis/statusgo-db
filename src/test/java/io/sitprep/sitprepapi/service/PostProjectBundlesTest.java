package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostPriority;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.AskBookmarkRepo;
import io.sitprep.sitprepapi.repo.FollowRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Bundles / projects (V51). Pure Mockito, mirroring {@link WorkOrderReopenRestoreTest}
 * / {@link PostListAssignedToTest} — the 19-arg construction + an active transaction
 * synchronization for the afterCommit broadcast that {@code moveToProject} triggers.
 *
 * <p>Two layers under test:</p>
 * <ul>
 *   <li>the DERIVED roll-up ({@link PostService#computeRollup}) — status counts, the
 *       DONE+CLOSED fold, the display label, and the triage feed (top open priority,
 *       any-open-life-safety, most-urgent-open child) that lets a project float up by
 *       its hottest still-open child. Life-safety + terminal children are the tricky
 *       cases and get dedicated coverage;</li>
 *   <li>the structural mutations — {@code moveToProject} (attach / detach / no-nesting
 *       / parent-must-be-a-project / same-group / manager-gate / 404) and {@code delete}
 *       detaching a project's children to standalone rather than cascade-deleting them.</li>
 * </ul>
 *
 * <p>The DB-level partial-index-free FK ({@code ON DELETE SET NULL}) can't be exercised
 * on the H2 entity-built test schema, so the app-level detach is what these assert (it
 * is also the prod belt-and-suspenders).</p>
 */
class PostProjectBundlesTest {

    private static final Long PROJECT = 500L;
    private static final Long CHILD = 501L;
    private static final String GROUP = "grp-1";
    private static final String ADMIN = "admin@x.com";
    private static final String MEMBER = "member@x.com";

    private PostRepo taskRepo;
    private GroupRepo groupRepo;
    private PostReactionService reactionService;
    private PostService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        groupRepo = mock(GroupRepo.class);
        reactionService = mock(PostReactionService.class);
        service = new PostService(
                taskRepo,
                mock(UserInfoRepo.class),
                mock(NominatimGeocodeService.class),
                mock(WebSocketMessageSender.class),
                mock(AlertModeService.class),
                mock(FollowRepo.class),
                mock(BlockService.class),
                reactionService,
                mock(PostCommentService.class),
                mock(StorageService.class),
                groupRepo,
                mock(PublisherPublishAuditService.class),
                mock(AgencyAuthorizationService.class),
                mock(PostConfirmRepo.class),
                mock(AskBookmarkRepo.class),
                mock(WorkOrderQuotaService.class),
                mock(AdminAuditLogService.class),
                mock(TaskAssigneeRepo.class),
                mock(TaskAssignmentService.class),
                mock(AgencyJurisdictionService.class),
                mock(CivicAgencyService.class));
        // Any fold that reaches withEngagement needs real (empty) reaction summaries
        // — a mock defaults them to null → NPE. Harmless when the path doesn't use them.
        when(reactionService.loadThankSummary(any(), any()))
                .thenReturn(new PostReactionService.ThankSummary(Map.of(), Set.of()));
        when(reactionService.loadReactionSummary(any(), any()))
                .thenReturn(new PostReactionService.ReactionSummary(Map.of(), Map.of()));
        // moveToProject → refetchAndBroadcast / delete both register an afterCommit sync.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ---------- builders ----------

    private Post child(Long id, PostStatus status, PostPriority priority) {
        Post t = new Post();
        t.setId(id);
        t.setKind("task");
        t.setGroupId(GROUP);
        t.setStatus(status);
        t.setPriority(priority);
        t.setProjectId(PROJECT);
        t.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return t;
    }

    private Post lifeSafetyChild(Long id, PostStatus status) {
        Post t = child(id, status, PostPriority.URGENT);
        t.setWorkDetails(Map.of("downedPowerLine", true)); // a real LIFE_SAFETY_FLAG
        return t;
    }

    private Post projectContainer() {
        Post p = new Post();
        p.setId(PROJECT);
        p.setKind("project");
        p.setGroupId(GROUP);
        p.setStatus(PostStatus.OPEN);
        p.setTitle("Smith residence"); // the recipient label
        return p;
    }

    private Post standaloneTask(Long id) {
        Post t = new Post();
        t.setId(id);
        t.setKind("task");
        t.setGroupId(GROUP);
        t.setStatus(PostStatus.OPEN);
        t.setPriority(PostPriority.MEDIUM);
        return t; // projectId stays null
    }

    private Group groupWithAdmin(String admin) {
        Group g = new Group();
        g.setGroupId(GROUP);
        g.setOwnerEmail("owner@x.com");
        g.setAdminEmails(List.of(admin));
        return g;
    }

    // =================== computeRollup — the derived roll-up ===================

    @Test
    void computeRollup_countsByStatus_labelAndDoneFoldsClosed() {
        List<Post> kids = List.of(
                child(1L, PostStatus.DONE, PostPriority.MEDIUM),
                child(2L, PostStatus.CLOSED, PostPriority.MEDIUM),      // folds into done
                child(3L, PostStatus.IN_PROGRESS, PostPriority.HIGH),
                child(4L, PostStatus.OPEN, PostPriority.LOW));

        PostDto.ProjectRollup r = PostService.computeRollup(kids);

        assertEquals(4, r.total());
        assertEquals(2, r.done(), "DONE + CLOSED both fold into done");
        assertEquals(1, r.inProgress());
        assertEquals(1, r.open());
        assertEquals(0, r.cancelled());
        assertEquals("2/4 done · 1 in progress · 1 open", r.label());
    }

    @Test
    void computeRollup_triageFeed_openLifeSafetyChildFloatsUp() {
        Post ls = lifeSafetyChild(10L, PostStatus.OPEN);                 // life-safety + URGENT + active
        Post hi = child(11L, PostStatus.OPEN, PostPriority.HIGH);
        Post doneUrgent = child(12L, PostStatus.DONE, PostPriority.URGENT); // terminal → ignored by triage

        PostDto.ProjectRollup r = PostService.computeRollup(List.of(hi, doneUrgent, ls));

        assertTrue(r.anyOpenLifeSafety(), "an OPEN life-safety child sets the flag");
        assertEquals(PostPriority.URGENT, r.topOpenPriority(), "top priority among ACTIVE children");
        assertEquals(10L, r.mostUrgentOpenChildId(), "the life-safety child is the most-urgent open");
    }

    @Test
    void computeRollup_terminalUrgentDoesNotDriveTriage() {
        // The only URGENT is DONE → the triage feed comes from the active HIGH child.
        PostDto.ProjectRollup r = PostService.computeRollup(List.of(
                child(20L, PostStatus.DONE, PostPriority.URGENT),
                child(21L, PostStatus.OPEN, PostPriority.HIGH)));

        assertEquals(PostPriority.HIGH, r.topOpenPriority());
        assertFalse(r.anyOpenLifeSafety());
        assertEquals(21L, r.mostUrgentOpenChildId());
    }

    @Test
    void computeRollup_emptyProject_noTasksYet() {
        PostDto.ProjectRollup r = PostService.computeRollup(List.of());

        assertEquals(0, r.total());
        assertEquals("No tasks yet", r.label());
        assertNull(r.topOpenPriority());
        assertNull(r.mostUrgentOpenChildId());
        assertFalse(r.anyOpenLifeSafety());
    }

    // =================== moveToProject — attach / detach / guards ===================

    @Test
    void moveToProject_attach_setsProjectId_whenManager() {
        when(taskRepo.findById(CHILD)).thenReturn(Optional.of(standaloneTask(CHILD)));
        when(taskRepo.findById(PROJECT)).thenReturn(Optional.of(projectContainer()));
        when(groupRepo.findByGroupId(GROUP)).thenReturn(Optional.of(groupWithAdmin(ADMIN)));

        service.moveToProject(CHILD, PROJECT, ADMIN);

        verify(taskRepo).updateProjectId(CHILD, PROJECT);
    }

    @Test
    void moveToProject_detach_nullsProjectId_andSkipsParentLookup() {
        when(taskRepo.findById(CHILD)).thenReturn(Optional.of(child(CHILD, PostStatus.OPEN, PostPriority.MEDIUM)));
        when(groupRepo.findByGroupId(GROUP)).thenReturn(Optional.of(groupWithAdmin(ADMIN)));

        service.moveToProject(CHILD, null, ADMIN);

        verify(taskRepo).updateProjectId(CHILD, null);
        verify(taskRepo, never()).findById(PROJECT); // detach never looks up a parent
    }

    @Test
    void moveToProject_rejectsNesting_whenMovingAProjectRow() {
        when(taskRepo.findById(PROJECT)).thenReturn(Optional.of(projectContainer()));

        assertThrows(IllegalArgumentException.class,
                () -> service.moveToProject(PROJECT, 999L, ADMIN));

        verify(taskRepo, never()).updateProjectId(any(), any());
    }

    @Test
    void moveToProject_rejectsParentThatIsNotAProject() {
        when(taskRepo.findById(CHILD)).thenReturn(Optional.of(standaloneTask(CHILD)));
        when(taskRepo.findById(PROJECT)).thenReturn(Optional.of(standaloneTask(PROJECT))); // a task, not a project
        when(groupRepo.findByGroupId(GROUP)).thenReturn(Optional.of(groupWithAdmin(ADMIN)));

        assertThrows(IllegalArgumentException.class,
                () -> service.moveToProject(CHILD, PROJECT, ADMIN));

        verify(taskRepo, never()).updateProjectId(any(), any());
    }

    @Test
    void moveToProject_rejectsCrossGroupParent() {
        Post foreignProject = projectContainer();
        foreignProject.setGroupId("other-grp");
        when(taskRepo.findById(CHILD)).thenReturn(Optional.of(standaloneTask(CHILD))); // group GROUP
        when(taskRepo.findById(PROJECT)).thenReturn(Optional.of(foreignProject));
        when(groupRepo.findByGroupId(GROUP)).thenReturn(Optional.of(groupWithAdmin(ADMIN)));

        assertThrows(IllegalArgumentException.class,
                () -> service.moveToProject(CHILD, PROJECT, ADMIN));

        verify(taskRepo, never()).updateProjectId(any(), any());
    }

    @Test
    void moveToProject_forbidsNonManager() {
        when(taskRepo.findById(CHILD)).thenReturn(Optional.of(standaloneTask(CHILD)));
        when(groupRepo.findByGroupId(GROUP)).thenReturn(Optional.of(groupWithAdmin(ADMIN)));

        // MEMBER is neither owner nor admin.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.moveToProject(CHILD, PROJECT, MEMBER));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(taskRepo, never()).updateProjectId(any(), any());
    }

    @Test
    void moveToProject_taskNotFound_404() {
        when(taskRepo.findById(CHILD)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.moveToProject(CHILD, PROJECT, ADMIN));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // =================== delete — children detach, never cascade ===================

    @Test
    void delete_project_detachesChildren_thenDeletesOnlyTheContainer() {
        Post project = projectContainer();
        when(taskRepo.findById(PROJECT)).thenReturn(Optional.of(project));

        service.delete(PROJECT);

        verify(taskRepo).detachChildrenOfProject(PROJECT); // children → standalone (project_id NULL)
        verify(taskRepo).delete(project);                  // only the container row is deleted
    }

    @Test
    void delete_standaloneTask_doesNotDetachAnything() {
        Post task = standaloneTask(CHILD);
        when(taskRepo.findById(CHILD)).thenReturn(Optional.of(task));

        service.delete(CHILD);

        verify(taskRepo, never()).detachChildrenOfProject(any());
        verify(taskRepo).delete(task);
    }
}
