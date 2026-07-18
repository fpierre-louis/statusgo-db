package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.domain.TaskAssignee;
import io.sitprep.sitprepapi.domain.TaskAssignee.Role;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.AskBookmarkRepo;
import io.sitprep.sitprepapi.repo.FollowRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Step-2 cross-agent contract (DOCS_STEP2 §5.7): {@code GET /api/me/posts?role=assignee}
 * → {@link PostService#listAssignedTo} reads {@code task_assignee} (the sole
 * authority) as the UNION of LEAD ∪ HELPER — NOT the single {@code assignee_email}
 * mirror, and NOT LEAD-only.
 *
 * <p>This is the one Step-2 read path that had no coverage, and it's load-bearing
 * ACROSS agents: the member "Tasks" tile (a DIFFERENT agent's FE surface) calls
 * this endpoint, so a HELPER must see the task they're helping on. A regression to
 * a Lead-only / mirror-based read would return an empty list here and fail
 * <b>silently in that other codebase</b> — hence a dedicated test. Pure Mockito;
 * the enrichment folds ({@code withAssignees}/{@code withEngagement}/…) run for
 * real, so the assertion also proves the HELPER role survives to the rendered DTO.</p>
 */
class PostListAssignedToTest {

    private static final Long TASK_ID = 8146L;
    private static final String HELPER = "helper@x.com";

    private PostRepo taskRepo;
    private TaskAssigneeRepo assigneeRepo;
    private PostReactionService reactionService;
    private PostService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        assigneeRepo = mock(TaskAssigneeRepo.class);
        reactionService = mock(PostReactionService.class);
        // 19-arg construction mirrors PostLiabilityGateTest; only the three collaborators
        // this path touches are held as fields, the rest are inert mocks.
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
                mock(GroupRepo.class),
                mock(PublisherPublishAuditService.class),
                mock(AgencyAuthorizationService.class),
                mock(PostConfirmRepo.class),
                mock(AskBookmarkRepo.class),
                mock(WorkOrderQuotaService.class),
                mock(AdminAuditLogService.class),
                assigneeRepo,
                mock(TaskAssignmentService.class),
                mock(AgencyJurisdictionService.class),
                mock(CivicAgencyService.class));
        // withEngagement dereferences the reaction summaries — hand it real empties
        // (a mock would default them to null → NPE inside the fold).
        when(reactionService.loadThankSummary(any(), any()))
                .thenReturn(new PostReactionService.ThankSummary(Map.of(), Set.of()));
        when(reactionService.loadReactionSummary(any(), any()))
                .thenReturn(new PostReactionService.ReactionSummary(Map.of(), Map.of()));
    }

    private Post groupTask() {
        Post t = new Post();
        t.setId(TASK_ID);
        t.setKind("task");
        t.setGroupId("grp-1");
        t.setStatus(PostStatus.OPEN);
        t.setRequesterEmail("requester@x.com");
        return t;
    }

    /** A PURE Helper on the task — no LEAD row, not the assignee_email mirror. */
    private TaskAssignee helperRow() {
        TaskAssignee a = new TaskAssignee();
        a.setPostId(TASK_ID);
        a.setEmail(HELPER);
        a.setRole(Role.HELPER);
        return a;
    }

    @Test
    void listAssignedTo_returnsTask_whereCallerIsHelperNotLead_unionRead() {
        // The ONLY link between HELPER and TASK_ID is a HELPER-role task_assignee
        // row. There is no LEAD row for HELPER and assignee_email is never consulted,
        // so a Lead-only or mirror-based implementation would return an empty list.
        when(assigneeRepo.findByEmailIgnoreCase(HELPER)).thenReturn(List.of(helperRow()));
        when(taskRepo.findAllById(List.of(TASK_ID))).thenReturn(List.of(groupTask()));
        when(assigneeRepo.findByPostIdIn(List.of(TASK_ID))).thenReturn(List.of(helperRow()));

        List<PostDto> result = service.listAssignedTo(HELPER);

        // 1) the HELPER's task IS returned — the LEAD ∪ HELPER union read works.
        assertEquals(1, result.size(), "a HELPER must see the task they're assigned to");
        assertEquals(TASK_ID, result.get(0).id());

        // 2) it was driven by task_assignee (the authority), via findByEmailIgnoreCase.
        verify(assigneeRepo).findByEmailIgnoreCase(HELPER);

        // 3) the folded roster carries the HELPER role through to the DTO the tile renders.
        List<PostDto.AssigneeDto> assignees = result.get(0).assignees();
        assertNotNull(assignees, "assignee roster must be folded onto the dto");
        assertTrue(
                assignees.stream().anyMatch(
                        a -> HELPER.equalsIgnoreCase(a.email()) && "HELPER".equals(a.role())),
                "the assignee roster must expose the caller as a HELPER");
    }
}
