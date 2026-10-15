package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.domain.TaskAssignee;
import io.sitprep.sitprepapi.dto.ManagedWorkOrdersDto;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.dto.WorkOrderSummaryDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Lane B6 task fields:
 * <ul>
 *   <li>TK-03: a helper's (or a non-primary lead's) row on the group board
 *       carries the folded roster with their role, so "Assigned to me" finds it.</li>
 *   <li>TK-09: {@code /me/work-orders?scope=managed} reads only the caller's
 *       managed groups, triages life-safety › urgent › newest, and pages.</li>
 *   <li>TK-11: the work-order summary counts real tasks by status and the
 *       open life-safety / urgent rows; members only.</li>
 * </ul>
 */
class PostAssignmentRoleTest {

    private static final String GROUP = "grp-1";
    private static final String GROUP2 = "grp-2";
    private static final String OWNER = "owner@x.com";
    private static final String LEAD = "lead@x.com";
    private static final String HELPER = "helper@x.com";
    private static final String STRANGER = "stranger@x.com";

    private PostRepo taskRepo;
    private GroupRepo groupRepo;
    private TaskAssigneeRepo assigneeRepo;
    private PostService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        groupRepo = mock(GroupRepo.class);
        assigneeRepo = mock(TaskAssigneeRepo.class);
        // The fixture's wiring, with a reaction service that answers the
        // engagement fold (the board lists run withEngagement).
        PostReactionService reactions = mock(PostReactionService.class);
        when(reactions.loadThankSummary(anyCollection(), any()))
                .thenReturn(new PostReactionService.ThankSummary(Map.of(), java.util.Set.of()));
        when(reactions.loadReactionSummary(anyCollection(), any()))
                .thenReturn(new PostReactionService.ReactionSummary(Map.of(), Map.of()));
        service = new PostService(taskRepo,
                mock(io.sitprep.sitprepapi.repo.UserInfoRepo.class),
                mock(NominatimGeocodeService.class),
                mock(io.sitprep.sitprepapi.websocket.WebSocketMessageSender.class),
                mock(AlertModeService.class),
                mock(io.sitprep.sitprepapi.repo.FollowRepo.class),
                mock(BlockService.class),
                reactions,
                mock(PostCommentService.class),
                mock(StorageService.class),
                groupRepo,
                mock(PublisherPublishAuditService.class),
                mock(AgencyAuthorizationService.class),
                mock(io.sitprep.sitprepapi.repo.PostConfirmRepo.class),
                mock(io.sitprep.sitprepapi.repo.PostFollowRepo.class),
                mock(io.sitprep.sitprepapi.repo.AskBookmarkRepo.class),
                mock(WorkOrderQuotaService.class),
                mock(AdminAuditLogService.class),
                assigneeRepo,
                mock(TaskAssignmentService.class),
                mock(AgencyJurisdictionService.class),
                mock(CivicAgencyService.class),
                new PostReadAuthorizer(groupRepo, assigneeRepo),
                mock(PostMentionService.class),
                mock(io.sitprep.sitprepapi.repo.AlertPostRepo.class),
                mock(io.sitprep.sitprepapi.repo.HazardReportRepo.class),
                mock(io.sitprep.sitprepapi.repo.HazardVoteRepo.class),
                mock(io.sitprep.sitprepapi.gamification.TokenEventPublisher.class));
        Group g = new Group();
        g.setGroupId(GROUP);
        g.setGroupName("Ward");
        g.setGroupType("Church");
        g.setOwnerEmail(OWNER);
        g.setAdminEmails(List.of());
        g.setMemberEmails(List.of(LEAD, HELPER));
        when(groupRepo.findByGroupId(GROUP)).thenReturn(Optional.of(g));
        ward = g;
    }

    private Group ward;

    private static Post task(long id, String groupId, Post.PostPriority priority, Map<String, Object> wd,
                             String createdAt) {
        Post p = new Post();
        p.setId(id);
        p.setKind("task");
        p.setGroupId(groupId);
        p.setRequesterEmail(OWNER);
        p.setTitle("Task " + id);
        p.setStatus(PostStatus.OPEN);
        p.setPriority(priority);
        p.setWorkDetails(wd);
        p.setCreatedAt(Instant.parse(createdAt));
        return p;
    }

    private static TaskAssignee assignee(long postId, String email, TaskAssignee.Role role, boolean primary) {
        TaskAssignee a = new TaskAssignee();
        a.setPostId(postId);
        a.setEmail(email);
        a.setRole(role);
        a.setPrimary(primary);
        return a;
    }

    // ------------------------------------------------------------- TK-03

    @Test
    void helpersRowOnTheBoardCarriesTheRosterWithTheirRole() {
        Post t = task(101L, GROUP, Post.PostPriority.MEDIUM, null, "2026-10-13T10:00:00Z");
        t.setAssigneeEmail(LEAD);
        when(taskRepo.findGroupBoard(eq(GROUP), eq(Post.PostStatus.ARCHIVED), any())).thenReturn(List.of(t));
        when(assigneeRepo.findByPostIdIn(anyList())).thenReturn(List.of(
                assignee(101L, LEAD, TaskAssignee.Role.LEAD, true),
                assignee(101L, HELPER, TaskAssignee.Role.HELPER, false)));

        List<PostDto> board = service.listByGroup(GROUP, null, HELPER);

        assertThat(board).hasSize(1);
        assertThat(board.get(0).assignees())
                .extracting(PostDto.AssigneeDto::email, PostDto.AssigneeDto::role)
                .contains(org.assertj.core.groups.Tuple.tuple(HELPER, "HELPER"),
                        org.assertj.core.groups.Tuple.tuple(LEAD, "LEAD"));
    }

    // ------------------------------------------------------------- TK-09

    @Test
    void managedBoard_onlyManagedGroups_triaged_andPaged() {
        Group other = new Group();
        other.setGroupId(GROUP2);
        other.setGroupName("School");
        when(groupRepo.findByOwnerEmailIgnoreCase(OWNER)).thenReturn(List.of(ward));
        when(groupRepo.findByAdminEmail(OWNER)).thenReturn(List.of(other));

        // Newest first, as the query returns them.
        Post newestPlain = task(4L, GROUP, Post.PostPriority.LOW, null, "2026-10-13T12:00:00Z");
        Post urgent = task(3L, GROUP2, Post.PostPriority.URGENT, null, "2026-10-13T11:00:00Z");
        Post lifeSafety = task(2L, GROUP, Post.PostPriority.MEDIUM,
                new java.util.HashMap<>(Map.of("occupantTrapped", true)), "2026-10-13T10:00:00Z");
        Post oldPlain = task(1L, GROUP2, Post.PostPriority.LOW, null, "2026-10-13T09:00:00Z");
        when(taskRepo.findManagedBoard(anyCollection(), eq(PostStatus.ARCHIVED), any()))
                .thenReturn(new ArrayList<>(List.of(newestPlain, urgent, lifeSafety, oldPlain)));

        ManagedWorkOrdersDto first = service.listManagedWorkOrders(OWNER, null, 0, 3);
        assertThat(first.items()).extracting(PostDto::id).containsExactly(2L, 3L, 4L);
        assertThat(first.total()).isEqualTo(4);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.groups()).extracting(ManagedWorkOrdersDto.GroupRef::groupId)
                .containsExactly(GROUP, GROUP2);

        ManagedWorkOrdersDto second = service.listManagedWorkOrders(OWNER, null, 3, 3);
        assertThat(second.items()).extracting(PostDto::id).containsExactly(1L);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void managedBoard_forSomeoneWhoManagesNothing_isEmptyAndQueriesNoPosts() {
        ManagedWorkOrdersDto out = service.listManagedWorkOrders(STRANGER, null, null, null);
        assertThat(out.items()).isEmpty();
        assertThat(out.groups()).isEmpty();
        verify(taskRepo, never()).findManagedBoard(anyCollection(), any(), any());
    }

    // ------------------------------------------------------------- TK-11

    @Test
    void summaryCountsRealTasksAndOpenLifeSafety_membersOnly() {
        when(taskRepo.countGroupTasksByStatus(GROUP)).thenReturn(List.of(
                new Object[]{PostStatus.OPEN, 3L},
                new Object[]{PostStatus.IN_PROGRESS, 1L},
                new Object[]{PostStatus.DONE, 2L},
                new Object[]{PostStatus.CLOSED, 1L},
                new Object[]{PostStatus.ARCHIVED, 1L}));
        when(taskRepo.findOpenGroupTasks(eq(GROUP), anyCollection())).thenReturn(List.of(
                task(1L, GROUP, Post.PostPriority.URGENT,
                        new java.util.HashMap<>(Map.of("gasLeakSuspected", true)), "2026-10-13T10:00:00Z"),
                task(2L, GROUP, Post.PostPriority.URGENT, null, "2026-10-13T10:00:00Z"),
                task(3L, GROUP, Post.PostPriority.LOW,
                        new java.util.HashMap<>(Map.of("siteSafeToEnter", "unsafeDoNotEnter")), "2026-10-13T10:00:00Z"),
                task(4L, GROUP, Post.PostPriority.LOW, null, "2026-10-13T10:00:00Z")));

        WorkOrderSummaryDto s = service.workOrderSummary(GROUP, HELPER);

        assertEquals(8, s.total());
        assertEquals(4, s.active());
        assertEquals(3, s.open());
        assertEquals(1, s.inProgress());
        assertEquals(3, s.done());
        assertEquals(1, s.archived());
        assertEquals(2, s.lifeSafetyOpen());
        assertEquals(2, s.urgentOpen());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.workOrderSummary(GROUP, STRANGER));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
