package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.SystemAccounts;
import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The community feed pins the post for an alert that COVERS the viewer
 * (owner, 2026-10-09), not the alert post whose own centroid happens to fall
 * inside the radius, and keeps it pinned while the alert is in effect.
 */
class PostAlertPinCoverageTest {

    private static final double LEHI_LAT = 40.39, LEHI_LNG = -111.85;

    private PostRepo taskRepo;
    private GroupRepo groupRepo;
    private PostReactionService reactionService;
    private BlockService blockService;
    private PostReadAuthorizer authorizer;
    private AlertPostRepo alertPostRepo;
    private AlertIngestService ingest;
    private PostService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        groupRepo = mock(GroupRepo.class);
        reactionService = mock(PostReactionService.class);
        blockService = mock(BlockService.class);
        authorizer = new PostReadAuthorizer(groupRepo, mock(TaskAssigneeRepo.class));
        alertPostRepo = mock(AlertPostRepo.class);
        ingest = mock(AlertIngestService.class);
        service = new PostService(
                taskRepo,
                mock(UserInfoRepo.class),
                mock(NominatimGeocodeService.class),
                mock(WebSocketMessageSender.class),
                mock(AlertModeService.class),
                mock(FollowRepo.class),
                blockService,
                reactionService,
                mock(PostCommentService.class),
                mock(StorageService.class),
                groupRepo,
                mock(PublisherPublishAuditService.class),
                mock(AgencyAuthorizationService.class),
                mock(PostConfirmRepo.class),
                mock(PostFollowRepo.class),
                mock(AskBookmarkRepo.class),
                mock(WorkOrderQuotaService.class),
                mock(AdminAuditLogService.class),
                mock(TaskAssigneeRepo.class),
                mock(TaskAssignmentService.class),
                mock(AgencyJurisdictionService.class),
                mock(CivicAgencyService.class),
                authorizer,
                org.mockito.Mockito.mock(PostMentionService.class),
                alertPostRepo,
                org.mockito.Mockito.mock(io.sitprep.sitprepapi.repo.HazardReportRepo.class),
                org.mockito.Mockito.mock(io.sitprep.sitprepapi.repo.HazardVoteRepo.class),
                org.mockito.Mockito.mock(io.sitprep.sitprepapi.gamification.TokenEventPublisher.class));
        service.setAlertIngestService(ingest);
        when(reactionService.loadThankSummary(any(), any()))
                .thenReturn(new PostReactionService.ThankSummary(Map.of(), Set.of()));
        when(reactionService.loadReactionSummary(any(), any()))
                .thenReturn(new PostReactionService.ReactionSummary(Map.of(), Map.of()));
        when(blockService.getBlockSet(any())).thenReturn(Set.of());
        when(taskRepo.findCommunityCandidates(any(), any())).thenReturn(List.of());
    }

    private static AlertIngestService.NormalizedAlert alert(String id, String event, String severity) {
        return TestAlerts.nws(event).id(id).severity(severity).build();
    }

    private void covering(AlertIngestService.NormalizedAlert... alerts) {
        when(ingest.getSnapshotForPoint(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new AlertIngestService.Snapshot(List.of(alerts), Instant.EPOCH, Instant.EPOCH));
    }

    /** A dispatcher post whose own point is ~300 km away: outside any feed radius. */
    private static Post alertPost(long id, Instant until) {
        Post p = new Post();
        p.setId(id);
        p.setKind("alert-update");
        p.setRequesterEmail(SystemAccounts.SITPREP_EMAIL);
        p.setStatus(PostStatus.OPEN);
        p.setLatitude(40.7);
        p.setLongitude(-113.5);
        p.setCreatedAt(Instant.now().minusSeconds(3 * 86_400)); // older than the old 24 h rule
        p.setEffectiveUntil(until);
        return p;
    }

    private static AlertPost link(String alertId, long postId) {
        AlertPost ap = new AlertPost();
        ap.setAlertId(alertId);
        ap.setPostId(postId);
        return ap;
    }

    @Test
    void aCoveringWatchIsPinnedEvenThoughItsPointIsOutsideTheRadiusAndOlderThan24h() {
        covering(alert("nws-FA-1", "Flood Watch", "Severe"));
        when(alertPostRepo.findActiveByAlertIdIn(any())).thenReturn(List.of(link("nws-FA-1", 7L)));
        when(taskRepo.findAllById(any())).thenReturn(List.of(alertPost(7L, Instant.now().plusSeconds(86_400))));

        List<PostDto> feed = service.discoverCommunity(LEHI_LAT, LEHI_LNG, 16, null, "viewer@x.com");

        assertFalse(feed.isEmpty());
        assertEquals(7L, feed.get(0).id());
        assertTrue(feed.get(0).community().pinned());
    }

    @Test
    void aWarningOutranksAWatch() {
        covering(alert("nws-FA-1", "Flood Watch", "Severe"), alert("nws-FF-2", "Flash Flood Warning", "Severe"));
        when(alertPostRepo.findActiveByAlertIdIn(any()))
                .thenReturn(List.of(link("nws-FA-1", 7L), link("nws-FF-2", 8L)));
        Instant until = Instant.now().plusSeconds(86_400);
        when(taskRepo.findAllById(any())).thenReturn(List.of(alertPost(7L, until), alertPost(8L, until)));

        List<PostDto> feed = service.discoverCommunity(LEHI_LAT, LEHI_LNG, 16, null, "viewer@x.com");

        assertEquals(8L, feed.get(0).id());
        assertEquals(1, feed.stream().filter(d -> d.community() != null && d.community().pinned()).count());
    }

    @Test
    void anAlertPastItsEffectiveUntilIsNotPinned() {
        covering(alert("nws-FA-1", "Flood Watch", "Severe"));
        when(alertPostRepo.findActiveByAlertIdIn(any())).thenReturn(List.of(link("nws-FA-1", 7L)));
        when(taskRepo.findAllById(any())).thenReturn(List.of(alertPost(7L, Instant.now().minusSeconds(60))));

        List<PostDto> feed = service.discoverCommunity(LEHI_LAT, LEHI_LNG, 16, null, "viewer@x.com");

        assertTrue(feed.stream().noneMatch(d -> d.community() != null && d.community().pinned()));
    }

    @Test
    void noCoveringAlertMeansNoPin() {
        covering();
        List<PostDto> feed = service.discoverCommunity(LEHI_LAT, LEHI_LNG, 16, null, "viewer@x.com");
        assertTrue(feed.isEmpty());
        verify(alertPostRepo, never()).findActiveByAlertIdIn(any());
    }

    @Test
    void anUpdatedWatchStillPinsThePostMadeFromTheMessageItReplaces() {
        // NWS updated the watch: the snapshot now carries a NEW id that lists
        // the original under `references`; the post was made from the original.
        covering(TestAlerts.nws("Flood Watch").id("nws-FA-UPDATE").references(List.of("nws-FA-ORIGINAL")).build());
        when(alertPostRepo.findActiveByAlertIdIn(any())).thenReturn(List.of(link("nws-FA-ORIGINAL", 7L)));
        when(taskRepo.findAllById(any())).thenReturn(List.of(alertPost(7L, null)));

        List<PostDto> feed = service.discoverCommunity(LEHI_LAT, LEHI_LNG, 16, null, "viewer@x.com");

        assertEquals(7L, feed.get(0).id());
        assertTrue(feed.get(0).community().pinned());
    }

    @Test
    void tierRankReadsTheNwsProductName() {
        assertEquals(3, PostService.alertTierRank("Flash Flood Warning"));
        assertEquals(3, PostService.alertTierRank("Evacuation Immediate"));
        assertEquals(2, PostService.alertTierRank("Flood Watch"));
        assertEquals(1, PostService.alertTierRank("Wind Advisory"));
        assertEquals(0, PostService.alertTierRank(null));
    }
}
