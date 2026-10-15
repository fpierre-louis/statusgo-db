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
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.Vtec;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S2 C6 (contract §7): a dispatched alert post carries its alert's lifecycle —
 * {@code community.alertLifecycle {state, current, reason}} — plus the canonical
 * {@code alertKey} and {@code alertSource} beside the bare {@code alertId}, all
 * folded in the EXISTING batched alert fold.
 *
 * <p>The point of the lifecycle is the S1 F3 follow-up: a closed alert post used
 * to say only "this left the feed", so the client could not tell an issuer's
 * cancellation from a replacement from a poll that simply stopped listing it.</p>
 */
class PostAlertLifecycleFoldTest {

    private static final double LAT = 40.66, LNG = -111.82;
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
        BlockService blocks = mock(BlockService.class);
        alertPostRepo = mock(AlertPostRepo.class);
        historyRepo = mock(AlertHistoryRepo.class);
        ingest = mock(AlertIngestService.class);
        service = new PostService(
                taskRepo, mock(UserInfoRepo.class), mock(NominatimGeocodeService.class),
                mock(WebSocketMessageSender.class), mock(AlertModeService.class), mock(FollowRepo.class),
                blocks, reactions, mock(PostCommentService.class), mock(StorageService.class), groupRepo,
                mock(PublisherPublishAuditService.class), mock(AgencyAuthorizationService.class),
                mock(PostConfirmRepo.class), mock(PostFollowRepo.class), mock(AskBookmarkRepo.class),
                mock(WorkOrderQuotaService.class), mock(AdminAuditLogService.class), mock(TaskAssigneeRepo.class),
                mock(TaskAssignmentService.class), mock(AgencyJurisdictionService.class),
                mock(CivicAgencyService.class), new PostReadAuthorizer(groupRepo, mock(TaskAssigneeRepo.class)),
                mock(PostMentionService.class), alertPostRepo, mock(HazardReportRepo.class),
                mock(HazardVoteRepo.class), mock(io.sitprep.sitprepapi.gamification.TokenEventPublisher.class));
        service.setAlertIngestService(ingest);

        AlertDispatchService dispatch = new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatch.loadTemplates();
        AlertHistoryService history = new AlertHistoryService(historyRepo, null, null, null);
        ObjectProvider<AlertDispatchService> dispatchProvider = mock(ObjectProvider.class);
        when(dispatchProvider.getIfAvailable()).thenReturn(dispatch);
        ObjectProvider<AlertHistoryService> historyProvider = mock(ObjectProvider.class);
        when(historyProvider.getIfAvailable()).thenReturn(history);
        service.setAlertTierSources(dispatchProvider, historyProvider, historyRepo);

        when(reactions.loadThankSummary(any(), any()))
                .thenReturn(new PostReactionService.ThankSummary(Map.of(), Set.of()));
        when(reactions.loadReactionSummary(any(), any()))
                .thenReturn(new PostReactionService.ReactionSummary(Map.of(), Map.of()));
        when(blocks.getBlockSet(any())).thenReturn(Set.of());
        when(ingest.getSnapshotForPoint(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new Snapshot(List.of(), Instant.EPOCH, Instant.EPOCH));
        when(historyRepo.findAllById(any())).thenReturn(List.of());
        when(taskRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(posts.get((Long) inv.getArgument(0))));
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private Post dispatched(long id) {
        Post p = new Post();
        p.setId(id);
        p.setKind("alert-update");
        p.setRequesterEmail(SystemAccounts.SITPREP_EMAIL);
        p.setStatus(PostStatus.OPEN);
        p.setLatitude(LAT);
        p.setLongitude(LNG);
        p.setCreatedAt(Instant.now().minusSeconds(3_600));
        posts.put(id, p);
        return p;
    }

    private static AlertPost link(String alertId, long postId, String resolvedReason) {
        AlertPost ap = new AlertPost();
        ap.setAlertId("NWS-" + alertId);
        ap.setGeocellId("84117");
        ap.setPostId(postId);
        if (resolvedReason != null) {
            ap.setResolvedAt(Instant.now().minusSeconds(600));
            ap.setResolvedReason(resolvedReason);
        }
        return ap;
    }

    private static AlertHistory stored(NormalizedAlert a) throws Exception {
        AlertHistory row = new AlertHistory();
        row.setAlertId(a.id());
        row.setSource(a.source());
        row.setEvent(a.event());
        row.setSeverity(a.severity());
        row.setPayload(JSON.writeValueAsString(a));
        return row;
    }

    /** NWS answered on the tick that built this snapshot (or did not). */
    private void live(boolean nwsFresh, NormalizedAlert... alerts) {
        Instant now = Instant.now();
        when(ingest.getSnapshot()).thenReturn(new Snapshot(List.of(alerts), now, now,
                Map.of("NWS", now, "USGS", now, "FEMA", now),
                nwsFresh ? Set.of("NWS", "USGS", "FEMA") : Set.of("USGS", "FEMA")));
    }

    private static Vtec vtec(String action) {
        return Vtec.parse("/O." + action + ".KSLC.FF.W.0042.261013T1800Z-261014T0300Z/").orElseThrow();
    }

    private static String future() { return Instant.now().plus(Duration.ofHours(3)).toString(); }

    private static String past() { return Instant.now().minus(Duration.ofHours(3)).toString(); }

    private JsonNode community(long postId) {
        PostDto dto = service.findDtoById(postId, VIEWER).orElseThrow();
        return JSON.valueToTree(dto).path("community");
    }

    // ── a live alert: the same lifecycle the feed card says ──────────────

    @Test
    void aLiveWarningPostIsCurrentAndCarriesItsCanonicalKey() {
        NormalizedAlert ffw = TestAlerts.nws("Flash Flood Warning").id("urn:oid:ffw-1")
                .vtec(List.of(vtec("NEW"))).endsAt(future()).build();
        live(true, ffw);
        dispatched(1L);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("urn:oid:ffw-1", 1L, null)));

        JsonNode c = community(1L);

        assertEquals("active", c.path("alertLifecycle").path("state").asText(null));
        assertTrue(c.path("alertLifecycle").path("current").asBoolean(false));
        assertEquals("vtec_new", c.path("alertLifecycle").path("reason").asText(null));
        assertEquals("NWS:urn:oid:ffw-1", c.path("alertKey").asText(null));
        assertEquals("NWS", c.path("alertSource").asText(null));
        // Unchanged neighbours of the new fields (U6 / R9 / map entry points).
        assertEquals("urn:oid:ffw-1", c.path("alertId").asText(null));
        assertEquals("warning", c.path("alertTier").asText(null));
    }

    // ── gone from the feed: how it ended ─────────────────────────────────

    @Test
    void anIssuerCancelInTheLiveFeedSaysCancelled() throws Exception {
        NormalizedAlert original = TestAlerts.nws("Flash Flood Warning").id("urn:oid:ffw-2")
                .vtec(List.of(vtec("NEW"))).endsAt(future()).build();
        // NWS ends a warning with an Update whose own VTEC is CAN.
        NormalizedAlert cancel = TestAlerts.nws("Flash Flood Warning").id("urn:oid:ffw-2-can")
                .messageType("Update").references(List.of("urn:oid:ffw-2"))
                .vtec(List.of(vtec("CAN"))).endsAt(future()).build();
        live(true, cancel);
        dispatched(2L);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("urn:oid:ffw-2", 2L, null)));
        when(historyRepo.findAllById(any())).thenReturn(List.of(stored(original)));

        JsonNode l = community(2L).path("alertLifecycle");

        assertEquals("cancelled", l.path("state").asText(null));
        assertFalse(l.path("current").asBoolean(true));
        assertEquals("vtec_can", l.path("reason").asText(null));
    }

    @Test
    void aReplacementInTheLiveFeedSaysSupersededNotCancelled() throws Exception {
        NormalizedAlert original = TestAlerts.nws("Flash Flood Warning").id("urn:oid:ffw-3")
                .vtec(List.of(vtec("NEW"))).endsAt(future()).build();
        NormalizedAlert update = TestAlerts.nws("Flash Flood Warning").id("urn:oid:ffw-3-con")
                .messageType("Update").references(List.of("urn:oid:ffw-3"))
                .vtec(List.of(vtec("CON"))).endsAt(future()).build();
        live(true, update);
        dispatched(3L);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("urn:oid:ffw-3", 3L, null)));
        when(historyRepo.findAllById(any())).thenReturn(List.of(stored(original)));

        JsonNode l = community(3L).path("alertLifecycle");

        assertEquals("superseded", l.path("state").asText(null));
        assertFalse(l.path("current").asBoolean(true));
        assertEquals("vtec_con", l.path("reason").asText(null));
    }

    @Test
    void goneFromAFreshPollWithNoIssuerWordIsNoLongerListedNeverCancelled() throws Exception {
        NormalizedAlert original = TestAlerts.nws("Flash Flood Warning").id("urn:oid:ffw-4")
                .endsAt(future()).build();
        live(true);
        dispatched(4L);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("urn:oid:ffw-4", 4L, null)));
        when(historyRepo.findAllById(any())).thenReturn(List.of(stored(original)));

        JsonNode l = community(4L).path("alertLifecycle");

        assertEquals("no_longer_listed", l.path("state").asText(null));
        assertFalse(l.path("current").asBoolean(true));
        assertEquals("not_listed", l.path("reason").asText(null));
    }

    @Test
    void goneWhileItsSourceIsDownIsUnknown_soTheFoldSaysNothing() throws Exception {
        // The boot snapshot / a failed NWS poll: absence means nothing yet.
        NormalizedAlert original = TestAlerts.nws("Flash Flood Warning").id("urn:oid:ffw-5")
                .endsAt(future()).build();
        live(false);
        dispatched(5L);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("urn:oid:ffw-5", 5L, null)));
        when(historyRepo.findAllById(any())).thenReturn(List.of(stored(original)));

        JsonNode c = community(5L);

        assertFalse(c.has("alertLifecycle"), "unknown is absent, never guessed: " + c);
        // The identity still rides: it comes from the post's own row.
        assertEquals("NWS:urn:oid:ffw-5", c.path("alertKey").asText(null));
    }

    @Test
    void pastItsOwnEndTimeIsExpired() throws Exception {
        NormalizedAlert original = TestAlerts.nws("Flash Flood Warning").id("urn:oid:ffw-6")
                .endsAt(past()).build();
        live(true);
        dispatched(6L);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("urn:oid:ffw-6", 6L, "expired")));
        when(historyRepo.findAllById(any())).thenReturn(List.of(stored(original)));

        JsonNode l = community(6L).path("alertLifecycle");

        assertEquals("expired", l.path("state").asText(null));
        assertEquals("expired", l.path("reason").asText(null));
    }

    // ── the stored resolved_reason, when the live evidence has moved on ──

    @Test
    void aStoredIssuerCancelOutranksTheAlertsOwnLaterExpiry() throws Exception {
        // Called off at noon, due to run until 3 pm, read at 6 pm: cancelled,
        // not expired. The cancelling message has long left the live feed.
        NormalizedAlert original = TestAlerts.nws("Flash Flood Warning").id("urn:oid:ffw-7")
                .endsAt(past()).build();
        live(true);
        dispatched(7L);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("urn:oid:ffw-7", 7L, "issuer_cancelled")));
        when(historyRepo.findAllById(any())).thenReturn(List.of(stored(original)));

        JsonNode l = community(7L).path("alertLifecycle");

        assertEquals("cancelled", l.path("state").asText(null));
        assertFalse(l.path("current").asBoolean(true));
        assertEquals("issuer_cancelled", l.path("reason").asText(null));
    }

    @Test
    void aStoredReplacementSaysSuperseded() {
        live(true);
        dispatched(8L);
        // History has swept the alert itself; only the stored reason remains.
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("urn:oid:ffw-8", 8L, "replaced")));

        JsonNode l = community(8L).path("alertLifecycle");

        assertEquals("superseded", l.path("state").asText(null));
        assertEquals("replaced", l.path("reason").asText(null));
    }

    @Test
    void aStoredNotListedHoldsEvenWhileTheSourceIsDownNow() {
        // resolveOnce only writes not_listed on a fresh poll, so the absence
        // was confirmed then; a failed poll now does not un-confirm it.
        live(false);
        dispatched(9L);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("urn:oid:ffw-9", 9L, "not_listed")));

        JsonNode l = community(9L).path("alertLifecycle");

        assertEquals("no_longer_listed", l.path("state").asText(null));
        assertEquals("not_listed", l.path("reason").asText(null));
    }

    @Test
    void liveIssuerEvidenceOutranksAStoredReplacement() throws Exception {
        // O was replaced by a CON (stored "replaced"); the live feed now holds
        // the CAN that ended the whole event, naming O in its references.
        NormalizedAlert original = TestAlerts.nws("Flash Flood Warning").id("urn:oid:ffw-10")
                .vtec(List.of(vtec("NEW"))).endsAt(future()).build();
        NormalizedAlert cancel = TestAlerts.nws("Flash Flood Warning").id("urn:oid:ffw-10-can")
                .messageType("Update").references(List.of("urn:oid:ffw-10", "urn:oid:ffw-10-con"))
                .vtec(List.of(vtec("CAN"))).endsAt(future()).build();
        live(true, cancel);
        dispatched(10L);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(link("urn:oid:ffw-10", 10L, "replaced")));
        when(historyRepo.findAllById(any())).thenReturn(List.of(stored(original)));

        assertEquals("cancelled", community(10L).path("alertLifecycle").path("state").asText(null));
    }

    // ── batched: the list fold costs the same two reads for N posts ──────

    @Test
    void aWholeFeedPageCostsOneAlertPostReadAndOneHistoryRead() throws Exception {
        NormalizedAlert liveWarn = TestAlerts.nws("Flash Flood Warning").id("urn:oid:b-live").endsAt(future()).build();
        NormalizedAlert goneA = TestAlerts.nws("Flash Flood Warning").id("urn:oid:b-gone-a").endsAt(future()).build();
        NormalizedAlert goneB = TestAlerts.nws("Tornado Warning").id("urn:oid:b-gone-b").endsAt(past()).build();
        NormalizedAlert cancel = TestAlerts.nws("Tornado Warning").id("urn:oid:b-can").messageType("Cancel")
                .references(List.of("urn:oid:b-gone-c")).build();
        live(true, liveWarn, cancel);
        List<Post> page = List.of(dispatched(21L), dispatched(22L), dispatched(23L), dispatched(24L), dispatched(25L));
        when(taskRepo.findCommunityCandidates(any(), any())).thenReturn(page);
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of(
                link("urn:oid:b-live", 21L, null),
                link("urn:oid:b-gone-a", 22L, null),
                link("urn:oid:b-gone-b", 23L, "expired"),
                link("urn:oid:b-gone-c", 24L, null),
                link("urn:oid:b-never", 25L, "replaced")));
        when(historyRepo.findAllById(any())).thenReturn(List.of(stored(goneA), stored(goneB)));

        Map<Long, JsonNode> c = new HashMap<>();
        for (PostDto d : service.discoverCommunity(LAT, LNG, 16, null, VIEWER)) {
            c.put(d.id(), JSON.valueToTree(d).path("community"));
        }

        assertEquals("active", c.get(21L).path("alertLifecycle").path("state").asText(null));
        assertEquals("no_longer_listed", c.get(22L).path("alertLifecycle").path("state").asText(null));
        assertEquals("expired", c.get(23L).path("alertLifecycle").path("state").asText(null));
        assertEquals("cancelled", c.get(24L).path("alertLifecycle").path("state").asText(null));
        assertEquals("cap_cancel", c.get(24L).path("alertLifecycle").path("reason").asText(null));
        assertEquals("superseded", c.get(25L).path("alertLifecycle").path("state").asText(null));

        verify(alertPostRepo, times(1)).findByPostIdIn(any());
        verify(historyRepo, times(1)).findAllById(any());
        // The list fold never reaches for the detail-only discussion reads.
        verify(alertPostRepo, never()).findReadableThreadPosts(any(), any());
    }

    @Test
    void aNeighboursAlertUpdateCarriesNoAlertContext() {
        live(true);
        Post neighbour = dispatched(30L);
        neighbour.setRequesterEmail("neighbor@example.com");
        when(alertPostRepo.findByPostIdIn(any())).thenReturn(List.of());

        JsonNode c = community(30L);

        assertFalse(c.has("alertLifecycle"));
        assertFalse(c.has("alertKey"));
        assertFalse(c.has("alertSource"));
    }
}
