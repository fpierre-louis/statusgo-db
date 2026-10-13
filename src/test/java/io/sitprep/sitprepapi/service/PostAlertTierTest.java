package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.sitprep.sitprepapi.constant.SystemAccounts;
import io.sitprep.sitprepapi.domain.AlertHistory;
import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R9 (DTO half): a dispatched alert post says which tier of product it came
 * from — warning, watch or advisory — so the client can stop painting every
 * alert post as a warning. The tier is the reviewed template's, the same one
 * the dispatcher used; it is folded in the existing batched alert fold, never
 * one lookup per post.
 */
class PostAlertTierTest {

    private static final double LEHI_LAT = 40.39, LEHI_LNG = -111.85;
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private PostRepo taskRepo;
    private PostReactionService reactionService;
    private BlockService blockService;
    private AlertPostRepo alertPostRepo;
    private AlertHistoryRepo historyRepo;
    private AlertIngestService ingest;
    private PostService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskRepo = mock(PostRepo.class);
        GroupRepo groupRepo = mock(GroupRepo.class);
        reactionService = mock(PostReactionService.class);
        blockService = mock(BlockService.class);
        alertPostRepo = mock(AlertPostRepo.class);
        historyRepo = mock(AlertHistoryRepo.class);
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
                new PostReadAuthorizer(groupRepo, mock(TaskAssigneeRepo.class)),
                mock(PostMentionService.class),
                alertPostRepo,
                mock(HazardReportRepo.class),
                mock(HazardVoteRepo.class),
                mock(io.sitprep.sitprepapi.gamification.TokenEventPublisher.class));
        service.setAlertIngestService(ingest);

        // The real templates, so the tier asserted is the reviewed one.
        AlertDispatchService dispatch = new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatch.loadTemplates();
        AlertHistoryService history = new AlertHistoryService(historyRepo, null, null, null);
        ObjectProvider<AlertDispatchService> dispatchProvider = mock(ObjectProvider.class);
        when(dispatchProvider.getIfAvailable()).thenReturn(dispatch);
        ObjectProvider<AlertHistoryService> historyProvider = mock(ObjectProvider.class);
        when(historyProvider.getIfAvailable()).thenReturn(history);
        service.setAlertTierSources(dispatchProvider, historyProvider, historyRepo);

        when(reactionService.loadThankSummary(any(), any()))
                .thenReturn(new PostReactionService.ThankSummary(Map.of(), Set.of()));
        when(reactionService.loadReactionSummary(any(), any()))
                .thenReturn(new PostReactionService.ReactionSummary(Map.of(), Map.of()));
        when(blockService.getBlockSet(any())).thenReturn(Set.of());
        when(ingest.getSnapshotForPoint(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new AlertIngestService.Snapshot(List.of(), Instant.EPOCH, Instant.EPOCH));
        when(historyRepo.findAllById(any())).thenReturn(List.of());
    }

    private static Post alertPost(long id, String author) {
        Post p = new Post();
        p.setId(id);
        p.setKind("alert-update");
        p.setRequesterEmail(author);
        p.setStatus(PostStatus.OPEN);
        p.setLatitude(LEHI_LAT);
        p.setLongitude(LEHI_LNG);
        p.setCreatedAt(Instant.now().minusSeconds(3_600));
        return p;
    }

    /** The dispatcher's key: {@code source + "-" + id} (AlertDispatchService). */
    private static AlertPost link(String source, String alertId, long postId) {
        AlertPost ap = new AlertPost();
        ap.setAlertId(source + "-" + alertId);
        ap.setPostId(postId);
        return ap;
    }

    private static AlertHistory stored(AlertIngestService.NormalizedAlert a) throws Exception {
        AlertHistory row = new AlertHistory();
        row.setAlertId(a.id());
        row.setSource(a.source());
        row.setEvent(a.event());
        row.setSeverity(a.severity());
        row.setPayload(JSON.writeValueAsString(a));
        return row;
    }

    private static JsonNode community(PostDto d) {
        return JSON.valueToTree(d).path("community");
    }

    private Map<Long, JsonNode> feedCommunities() {
        List<PostDto> feed = service.discoverCommunity(LEHI_LAT, LEHI_LNG, 16, null, "viewer@x.com");
        Map<Long, JsonNode> out = new HashMap<>();
        for (PostDto d : feed) out.put(d.id(), community(d));
        return out;
    }

    @Test
    void aLiveWatchPostSaysWatchAndALiveWarningPostSaysWarning() {
        var watch = TestAlerts.nwsWatch("Flood Watch").id("urn:oid:watch-1").build();
        var warning = TestAlerts.nws("Flash Flood Warning").id("urn:oid:warn-1").build();
        when(ingest.getSnapshot()).thenReturn(new AlertIngestService.Snapshot(List.of(watch, warning), Instant.EPOCH, Instant.EPOCH));
        when(taskRepo.findCommunityCandidates(any(), any())).thenReturn(List.of(
                alertPost(1L, SystemAccounts.SITPREP_EMAIL), alertPost(2L, SystemAccounts.SITPREP_EMAIL)));
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(
                link("NWS", "urn:oid:watch-1", 1L), link("NWS", "urn:oid:warn-1", 2L)));

        Map<Long, JsonNode> c = feedCommunities();

        assertEquals("watch", c.get(1L).path("alertTier").asText(null));
        assertEquals("warning", c.get(2L).path("alertTier").asText(null));
        // Both answered from the live snapshot: no history read at all.
        verify(historyRepo, never()).findAllById(any());
    }

    @Test
    void postsWhoseAlertLeftTheSnapshotAreResolvedFromHistoryInOneLookup() throws Exception {
        var watch = TestAlerts.nwsWatch("Winter Storm Watch").id("urn:oid:gone-watch").build();
        var warning = TestAlerts.nws("Tornado Warning").id("urn:oid:gone-warn").build();
        var quake = TestAlerts.usgs("M5.6 — 14km E of Encinitas, CA").id("us7000abcd").build();
        when(ingest.getSnapshot()).thenReturn(new AlertIngestService.Snapshot(List.of(), Instant.EPOCH, Instant.EPOCH));
        when(taskRepo.findCommunityCandidates(any(), any())).thenReturn(List.of(
                alertPost(11L, SystemAccounts.SITPREP_EMAIL),
                alertPost(12L, SystemAccounts.SITPREP_EMAIL),
                alertPost(13L, SystemAccounts.SITPREP_EMAIL),
                alertPost(14L, SystemAccounts.SITPREP_EMAIL)));
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(
                link("NWS", "urn:oid:gone-watch", 11L),
                link("NWS", "urn:oid:gone-warn", 12L),
                link("USGS", "us7000abcd", 13L),
                link("NWS", "urn:oid:never-recorded", 14L)));
        when(historyRepo.findAllById(any())).thenReturn(List.of(stored(watch), stored(warning), stored(quake)));

        Map<Long, JsonNode> c = feedCommunities();

        assertEquals("watch", c.get(11L).path("alertTier").asText(null));
        assertEquals("warning", c.get(12L).path("alertTier").asText(null));
        assertEquals("warning", c.get(13L).path("alertTier").asText(null)); // the USGS template's tier
        // Unresolvable is absent, not guessed.
        assertFalse(c.get(14L).has("alertTier"));

        // Four posts, one alert-post read and one history read.
        verify(alertPostRepo, times(1)).findByPostIdIn(any());
        verify(historyRepo, times(1)).findAllById(any());
    }

    @Test
    void aHistoryRowFromAnotherSourceWithTheSameBareIdIsNotThisAlert() throws Exception {
        // alert_history is keyed on the bare id; the post's key is source-qualified.
        var other = TestAlerts.usgs("M6.0 — 10km N of Ridgecrest, CA").id("shared-id").build();
        when(ingest.getSnapshot()).thenReturn(new AlertIngestService.Snapshot(List.of(), Instant.EPOCH, Instant.EPOCH));
        when(taskRepo.findCommunityCandidates(any(), any())).thenReturn(List.of(alertPost(21L, SystemAccounts.SITPREP_EMAIL)));
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("NWS", "shared-id", 21L)));
        when(historyRepo.findAllById(any())).thenReturn(List.of(stored(other)));

        assertFalse(feedCommunities().get(21L).has("alertTier"));
    }

    @Test
    void aNeighboursAlertUpdateAndOtherKindsCarryNoTier() {
        when(ingest.getSnapshot()).thenReturn(new AlertIngestService.Snapshot(List.of(), Instant.EPOCH, Instant.EPOCH));
        Post plain = alertPost(31L, "neighbor@example.com");
        plain.setKind("post");
        when(taskRepo.findCommunityCandidates(any(), any())).thenReturn(List.of(
                alertPost(30L, "neighbor@example.com"), plain));

        Map<Long, JsonNode> c = feedCommunities();

        assertFalse(c.get(30L).has("alertTier"));
        assertFalse(c.get(31L).has("alertTier"));
        verify(historyRepo, never()).findAllById(any());
    }
}
