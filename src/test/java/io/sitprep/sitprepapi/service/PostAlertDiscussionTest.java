package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.sitprep.sitprepapi.constant.PostKind;
import io.sitprep.sitprepapi.constant.SystemAccounts;
import io.sitprep.sitprepapi.domain.AlertHistory;
import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.Vtec;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S2 C6 (contract §7): the post detail of a dispatched alert-update carries
 * {@code alertDiscussion: { postIds, participants }} — every readable post of
 * the alert's THREAD (all geocells, every revision in hand), and how many
 * distinct people take part, counted in ONE query.
 *
 * <p>The thread is the alert's, not the post's: NWS replaces a warning's
 * message with a new id on every update and the dispatcher makes a new post
 * per id, so the conversation about one hazard is spread over several posts.
 * The privacy half of the count (hidden authors, group-only posts) is the
 * query's, proved on Postgres in {@code AlertThreadParticipantsPostgresIT}.</p>
 */
class PostAlertDiscussionTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String VIEWER = "viewer@example.com";

    private PostRepo taskRepo;
    private AlertPostRepo alertPostRepo;
    private AlertHistoryRepo historyRepo;
    private AlertIngestService ingest;
    private PostService service;
    private final Map<Long, Post> posts = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskRepo = mock(PostRepo.class);
        GroupRepo groupRepo = mock(GroupRepo.class);
        PostReactionService reactions = mock(PostReactionService.class);
        alertPostRepo = mock(AlertPostRepo.class);
        historyRepo = mock(AlertHistoryRepo.class);
        ingest = mock(AlertIngestService.class);
        service = new PostService(
                taskRepo, mock(UserInfoRepo.class), mock(NominatimGeocodeService.class),
                mock(WebSocketMessageSender.class), mock(AlertModeService.class), mock(FollowRepo.class),
                mock(BlockService.class), reactions, mock(PostCommentService.class), mock(StorageService.class),
                groupRepo, mock(PublisherPublishAuditService.class), mock(AgencyAuthorizationService.class),
                mock(PostConfirmRepo.class), mock(PostFollowRepo.class), mock(AskBookmarkRepo.class),
                mock(WorkOrderQuotaService.class), mock(AdminAuditLogService.class), mock(TaskAssigneeRepo.class),
                mock(TaskAssignmentService.class), mock(AgencyJurisdictionService.class),
                mock(CivicAgencyService.class), new PostReadAuthorizer(groupRepo, mock(TaskAssigneeRepo.class)),
                mock(PostMentionService.class), alertPostRepo, mock(HazardReportRepo.class),
                mock(HazardVoteRepo.class), mock(io.sitprep.sitprepapi.gamification.TokenEventPublisher.class));
        service.setAlertIngestService(ingest);
        AlertDispatchService dispatch = new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatch.loadTemplates();
        ObjectProvider<AlertDispatchService> dispatchProvider = mock(ObjectProvider.class);
        when(dispatchProvider.getIfAvailable()).thenReturn(dispatch);
        ObjectProvider<AlertHistoryService> historyProvider = mock(ObjectProvider.class);
        when(historyProvider.getIfAvailable()).thenReturn(new AlertHistoryService(historyRepo, null, null, null));
        service.setAlertTierSources(dispatchProvider, historyProvider, historyRepo);

        when(reactions.loadThankSummary(any(), any()))
                .thenReturn(new PostReactionService.ThankSummary(Map.of(), Set.of()));
        when(reactions.loadReactionSummary(any(), any()))
                .thenReturn(new PostReactionService.ReactionSummary(Map.of(), Map.of()));
        when(historyRepo.findAllById(any())).thenReturn(List.of());
        when(taskRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(posts.get((Long) inv.getArgument(0))));
    }

    private Post post(long id, String kind, String author) {
        Post p = new Post();
        p.setId(id);
        p.setKind(kind);
        p.setRequesterEmail(author);
        p.setStatus(PostStatus.OPEN);
        p.setCreatedAt(Instant.now().minusSeconds(600));
        posts.put(id, p);
        return p;
    }

    private static AlertPost link(String alertId, long postId) {
        AlertPost ap = new AlertPost();
        ap.setAlertId(alertId);
        ap.setGeocellId("84117");
        ap.setPostId(postId);
        return ap;
    }

    private record Ref(String getAlertId, Long getPostId) implements AlertPostRepo.ThreadPostRef {}

    private void live(NormalizedAlert... alerts) {
        Instant now = Instant.now();
        when(ingest.getSnapshot()).thenReturn(new Snapshot(List.of(alerts), now, now));
    }

    private static Vtec vtec(String action, String etn) {
        return Vtec.parse("/O." + action + ".KSLC.FF.W." + etn + ".261013T1800Z-261014T0300Z/").orElseThrow();
    }

    private static String future() { return Instant.now().plus(Duration.ofHours(3)).toString(); }

    @SuppressWarnings("unchecked")
    private Set<String> askedKeys() {
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(alertPostRepo).findReadableThreadPosts(keys.capture(), eq(SystemAccounts.SITPREP_EMAIL));
        return new HashSet<>(keys.getValue());
    }

    @Test
    void theDetailCarriesEveryReadablePostOfTheThreadAndOneParticipantCount() {
        // O was issued, then continued by a CON that names it; a sibling segment
        // of the same VTEC event was dispatched under its own id elsewhere.
        NormalizedAlert con = TestAlerts.nws("Flash Flood Warning").id("urn:oid:o-con").messageType("Update")
                .references(List.of("urn:oid:o")).vtec(List.of(vtec("CON", "0042"))).endsAt(future()).build();
        NormalizedAlert sibling = TestAlerts.nws("Flash Flood Warning").id("urn:oid:sib")
                .vtec(List.of(vtec("NEW", "0042"))).endsAt(future()).build();
        NormalizedAlert unrelated = TestAlerts.nws("Flash Flood Warning").id("urn:oid:other")
                .vtec(List.of(vtec("NEW", "0043"))).endsAt(future()).build();
        live(con, sibling, unrelated);
        post(1L, "alert-update", SystemAccounts.SITPREP_EMAIL);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("NWS-urn:oid:o", 1L)));
        when(alertPostRepo.findReadableThreadPosts(any(), any())).thenReturn(List.of(
                new Ref("NWS-urn:oid:o", 1L), new Ref("NWS-urn:oid:o-con", 7L),
                new Ref("NWS-urn:oid:sib", 4L), new Ref("NWS-urn:oid:o-con", 7L)));
        when(alertPostRepo.countThreadParticipants(any(), any(), any())).thenReturn(6L);

        JsonNode json = JSON.valueToTree(service.findDtoById(1L, VIEWER).orElseThrow());

        JsonNode d = json.path("alertDiscussion");
        assertThat(d.isObject()).as("top-level alertDiscussion: " + json).isTrue();
        assertThat(d.path("postIds")).extracting(JsonNode::asText).containsExactly("1", "4", "7");
        assertThat(d.path("participants").asLong()).isEqualTo(6L);
        assertThat(json.path("community").has("alertDiscussion")).isFalse();

        // The thread: O, the CON that names it (and what it names), the VTEC
        // sibling — never the unrelated event.
        assertThat(askedKeys()).contains("NWS-urn:oid:o", "NWS-urn:oid:o-con", "NWS-urn:oid:sib")
                .doesNotContain("NWS-urn:oid:other");

        // ONE participant count, over exactly the readable posts, excluding
        // SitPrep itself and the personal-scope kinds no stranger can read.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> personal = ArgumentCaptor.forClass(Collection.class);
        verify(alertPostRepo, times(1)).countThreadParticipants(ids.capture(),
                eq(SystemAccounts.SITPREP_EMAIL), personal.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(1L, 4L, 7L);
        assertThat(personal.getValue()).contains(PostKind.TASK.wire(), PostKind.PROJECT.wire());
        verify(alertPostRepo, times(1)).findReadableThreadPosts(any(), any());
    }

    @Test
    void anAlertThatLeftTheFeedThreadsThroughItsOwnRecordedReferences() throws Exception {
        // Read after the event: nothing live names O, but O's own record names
        // the message it replaced.
        NormalizedAlert o = TestAlerts.nws("Flash Flood Warning").id("urn:oid:o2").messageType("Update")
                .references(List.of("urn:oid:o2-first")).endsAt(future()).build();
        live();
        AlertHistory row = new AlertHistory();
        row.setAlertId(o.id());
        row.setSource(o.source());
        row.setPayload(JSON.writeValueAsString(o));
        when(historyRepo.findAllById(any())).thenReturn(List.of(row));
        post(2L, "alert-update", SystemAccounts.SITPREP_EMAIL);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("NWS-urn:oid:o2", 2L)));
        when(alertPostRepo.findReadableThreadPosts(any(), any())).thenReturn(List.of(
                new Ref("NWS-urn:oid:o2-first", 3L), new Ref("NWS-urn:oid:o2", 2L)));
        when(alertPostRepo.countThreadParticipants(any(), any(), any())).thenReturn(2L);

        JsonNode d = JSON.valueToTree(service.findDtoById(2L, VIEWER).orElseThrow()).path("alertDiscussion");

        assertThat(askedKeys()).containsExactlyInAnyOrder("NWS-urn:oid:o2", "NWS-urn:oid:o2-first");
        assertThat(d.path("postIds")).extracting(JsonNode::asText).containsExactly("2", "3");
        assertThat(d.path("participants").asLong()).isEqualTo(2L);
    }

    @Test
    void noReadablePostsMeansNoParticipantQueryAndZero() {
        live();
        post(5L, "alert-update", SystemAccounts.SITPREP_EMAIL);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("NWS-urn:oid:p", 5L)));
        when(alertPostRepo.findReadableThreadPosts(any(), any())).thenReturn(List.of());

        JsonNode d = JSON.valueToTree(service.findDtoById(5L, VIEWER).orElseThrow()).path("alertDiscussion");

        assertThat(d.path("postIds").size()).isZero();
        assertThat(d.path("participants").asLong()).isZero();
        verify(alertPostRepo, never()).countThreadParticipants(any(), any(), any());
    }

    @Test
    void otherPostsAndANeighboursAlertUpdateCarryNoDiscussionAndCostNothing() {
        live();
        post(10L, "post", "neighbor@example.com");
        post(11L, "alert-update", "neighbor@example.com");
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of());

        assertThat(JSON.valueToTree(service.findDtoById(10L, VIEWER).orElseThrow()).has("alertDiscussion")).isFalse();
        assertThat(JSON.valueToTree(service.findDtoById(11L, VIEWER).orElseThrow()).has("alertDiscussion")).isFalse();
        verify(alertPostRepo, never()).findReadableThreadPosts(any(), any());
        verify(alertPostRepo, never()).countThreadParticipants(any(), any(), any());
    }

    @Test
    void theFeedListNeverCarriesTheDiscussion() {
        live();
        Post p = post(20L, "alert-update", SystemAccounts.SITPREP_EMAIL);
        p.setLatitude(40.66);
        p.setLongitude(-111.82);
        when(ingest.getSnapshotForPoint(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new Snapshot(List.of(), Instant.EPOCH, Instant.EPOCH));
        when(taskRepo.findCommunityCandidates(any(), any())).thenReturn(List.of(p));
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("NWS-urn:oid:q", 20L)));

        List<PostDto> feed = service.discoverCommunity(40.66, -111.82, 16, null, VIEWER);

        assertThat(feed).isNotEmpty();
        assertThat(JSON.valueToTree(feed.get(0)).has("alertDiscussion")).isFalse();
        verify(alertPostRepo, never()).countThreadParticipants(any(), any(), any());
    }

    @Test
    void aClientEchoingTheDetailBackDoesNotBreakDeserialization() throws Exception {
        // The property is derived (READ_ONLY): a PostDto read back from JSON —
        // as a test or a cache might — ignores it rather than failing.
        live();
        post(30L, "alert-update", SystemAccounts.SITPREP_EMAIL);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("NWS-urn:oid:r", 30L)));
        when(alertPostRepo.findReadableThreadPosts(any(), any())).thenReturn(List.of(new Ref("NWS-urn:oid:r", 30L)));
        when(alertPostRepo.countThreadParticipants(any(), any(), any())).thenReturn(1L);

        String wire = JSON.writeValueAsString(service.findDtoById(30L, VIEWER).orElseThrow());
        ObjectMapper strict = new ObjectMapper().registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        PostDto back = strict.readValue(wire, PostDto.class);
        assertThat(back.id()).isEqualTo(30L);
        assertThat(back.community().alertKey()).isEqualTo("NWS:urn:oid:r");
    }
}
