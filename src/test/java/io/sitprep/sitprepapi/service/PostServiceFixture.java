package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.repo.AskBookmarkRepo;
import io.sitprep.sitprepapi.repo.FollowRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.repo.PostFollowRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;

import static org.mockito.Mockito.mock;

/**
 * Builds a {@link PostService} over mocks with a REAL {@link PostReadAuthorizer},
 * for the B3 authorization tests (claim, create). Same wiring as
 * {@code PostGroupScopeAuthorizationTest}.
 */
final class PostServiceFixture {

    private PostServiceFixture() {}

    static PostService build(PostRepo taskRepo, GroupRepo groupRepo, TaskAssigneeRepo assigneeRepo,
                             WorkOrderQuotaService quota, TaskAssignmentService assignments) {
        return new PostService(
                taskRepo,
                mock(UserInfoRepo.class),
                mock(NominatimGeocodeService.class),
                mock(WebSocketMessageSender.class),
                mock(AlertModeService.class),
                mock(FollowRepo.class),
                mock(BlockService.class),
                mock(PostReactionService.class),
                mock(PostCommentService.class),
                mock(StorageService.class),
                groupRepo,
                mock(PublisherPublishAuditService.class),
                mock(AgencyAuthorizationService.class),
                mock(PostConfirmRepo.class),
                mock(PostFollowRepo.class),
                mock(AskBookmarkRepo.class),
                quota,
                mock(AdminAuditLogService.class),
                assigneeRepo,
                assignments,
                mock(AgencyJurisdictionService.class),
                mock(CivicAgencyService.class),
                new PostReadAuthorizer(groupRepo, assigneeRepo),
                mock(PostMentionService.class),
                mock(io.sitprep.sitprepapi.repo.AlertPostRepo.class),
                mock(io.sitprep.sitprepapi.repo.HazardReportRepo.class),
                mock(io.sitprep.sitprepapi.repo.HazardVoteRepo.class),
                mock(io.sitprep.sitprepapi.gamification.TokenEventPublisher.class));
    }
}
