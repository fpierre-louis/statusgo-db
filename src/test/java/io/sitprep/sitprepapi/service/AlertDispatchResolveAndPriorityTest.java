package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostPriority;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.AlertPostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dispatcher acts on what upstream actually said (Phase 3 D5, R9).
 *
 * <ul>
 *   <li><b>Resolve only on a fresh source.</b> {@code resolveOnce} cancelled
 *       any tracked post whose alert was missing from the snapshot. A failed
 *       or malformed poll is not "the alert ended", and neither is the empty
 *       snapshot a just-booted server holds before its first poll — both used
 *       to cancel live warnings.</li>
 *   <li><b>Priority follows tier.</b> Every dispatched post was URGENT, so a
 *       Flood Watch ranked with a Tornado Warning.</li>
 *   <li><b>No SitPrep headline on the official path.</b> The title fallback
 *       ended in the template's own headline — SitPrep copy under an
 *       official-only post.</li>
 * </ul>
 */
class AlertDispatchResolveAndPriorityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlertIngestService ingest;
    private AlertPostRepo alertPostRepo;
    private PostService posts;
    private AlertDispatchService dispatcher;
    private final Map<String, List<AlertPost>> tracked = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        ingest = mock(AlertIngestService.class);
        alertPostRepo = mock(AlertPostRepo.class);
        posts = mock(PostService.class);
        UserInfoRepo users = mock(UserInfoRepo.class);
        NominatimGeocodeService geocode = mock(NominatimGeocodeService.class);
        dispatcher = new AlertDispatchService(ingest, alertPostRepo, posts, users, geocode,
                mock(NotificationService.class), null, null);
        dispatcher.loadTemplates();

        when(alertPostRepo.findActiveAlertIds()).thenAnswer(inv -> new ArrayList<>(tracked.keySet()));
        when(alertPostRepo.findActiveByAlertId(anyString()))
                .thenAnswer(inv -> tracked.getOrDefault((String) inv.getArgument(0), List.of()));
        when(alertPostRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(
                new NominatimGeocodeService.Place(null, "Norman", null, "OK", "us", "73069", "73069"));
        when(posts.create(any(), anyString())).thenReturn(postDto(7L));
        when(users.findPushablesWithLocation(any())).thenReturn(List.of());
    }

    /** PostDto is a ~90-component record: id set, rest defaulted (as EarthquakePushTest does). */
    private static PostDto postDto(Long id) throws Exception {
        var comps = PostDto.class.getRecordComponents();
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            Class<?> t = comps[i].getType();
            types[i] = t;
            if ("id".equals(comps[i].getName())) args[i] = id;
            else if (t == boolean.class) args[i] = false;
            else if (t == int.class) args[i] = 0;
            else if (t == long.class) args[i] = 0L;
            else if (t == double.class) args[i] = 0d;
            else if (t.isPrimitive()) throw new IllegalStateException("unhandled primitive " + t);
        }
        return PostDto.class.getDeclaredConstructor(types).newInstance(args);
    }

    private AlertPost track(String alertId, long postId, Instant expiresAt) {
        AlertPost ap = new AlertPost();
        ap.setAlertId(alertId);
        ap.setPostId(postId);
        ap.setGeocellId("73069");
        ap.setExpiresAt(expiresAt);
        tracked.computeIfAbsent(alertId, k -> new ArrayList<>()).add(ap);
        return ap;
    }

    private void snapshot(Set<String> fresh, NormalizedAlert... alerts) {
        Instant now = Instant.now();
        when(ingest.getSnapshot()).thenReturn(new Snapshot(List.of(alerts), now, now,
                Map.of("NWS", now, "USGS", now, "FEMA", now), fresh));
    }

    private static Instant later() { return Instant.now().plus(Duration.ofHours(2)); }

    // ── (d) resolve only on a freshly polled source ──────────────────────

    @Test
    void anAlertGoneFromAFreshlyPolledSourceIsResolved() {
        AlertPost ap = track("NWS-gone", 11L, later());
        snapshot(Set.of("NWS", "USGS", "FEMA"));

        assertThat(dispatcher.resolveOnce()).isEqualTo(1);
        verify(posts).cancel(11L);
        assertThat(ap.getResolvedAt()).isNotNull();
    }

    @Test
    void anAlertMissingBecauseItsSourceFailedIsNotResolved() {
        AlertPost ap = track("NWS-still-live", 12L, later());
        snapshot(Set.of("USGS", "FEMA"));   // NWS failed this tick

        assertThat(dispatcher.resolveOnce()).isZero();
        verify(posts, never()).cancel(anyLong());
        assertThat(ap.getResolvedAt()).isNull();
    }

    @Test
    void theEmptyBootSnapshotResolvesNothing() {
        track("NWS-a", 13L, later());
        track("USGS-b", 14L, later());
        when(ingest.getSnapshot()).thenReturn(Snapshot.empty());

        assertThat(dispatcher.resolveOnce())
                .as("no poll has run yet — absence means nothing")
                .isZero();
        verify(posts, never()).cancel(anyLong());
    }

    @Test
    void freshnessIsJudgedPerSource() {
        track("NWS-x", 15L, later());
        AlertPost quake = track("USGS-y", 16L, later());
        snapshot(Set.of("USGS"));   // NWS + FEMA failed

        assertThat(dispatcher.resolveOnce()).isEqualTo(1);
        verify(posts).cancel(16L);
        verify(posts, never()).cancel(15L);
        assertThat(quake.getResolvedAt()).isNotNull();
    }

    @Test
    void aPassedEndTimeStillResolves_evenWhileTheSourceIsDown() {
        // Expiry is the issuer's own declared end, not an inference from the
        // feed — an ended warning must not stay pinned through an outage.
        AlertPost ap = track("NWS-ended", 17L, Instant.now().minus(Duration.ofMinutes(1)));
        snapshot(Set.of());

        assertThat(dispatcher.resolveOnce()).isEqualTo(1);
        verify(posts).cancel(17L);
        assertThat(ap.getResolvedAt()).isNotNull();
    }

    @Test
    void anAlertStillListedIsKept() {
        NormalizedAlert live = TestAlerts.nws("Tornado Warning").id("still").build();
        track("NWS-still", 18L, later());
        snapshot(Set.of("NWS", "USGS", "FEMA"), live);

        assertThat(dispatcher.resolveOnce()).isZero();
        verify(posts, never()).cancel(anyLong());
    }

    // ── (e) priority from tier ───────────────────────────────────────────

    private NormalizedAlert nwsWithPolygon(String event, String urgency, String certainty, String response)
            throws Exception {
        Instant now = Instant.now();
        String json = """
                {"type":"FeatureCollection","features":[{
                  "type":"Feature","id":"urn:%1$s",
                  "properties":{"id":"urn:%1$s","event":"%2$s","status":"Actual","messageType":"Alert",
                    "severity":"Severe","urgency":"%3$s","certainty":"%4$s","response":"%5$s",
                    "headline":"%2$s issued","description":"Official description.",
                    "areaDesc":"Cleveland","effective":"%6$s","expires":"%7$s"},
                  "geometry":{"type":"Polygon","coordinates":[[[-97.5,35.2],[-97.3,35.2],[-97.3,35.4],[-97.5,35.2]]]}
                }]}""".formatted(event.replace(' ', '-'), event, urgency, certainty, response,
                now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(2)));
        return new AlertIngestService(new NwsZoneService()).parseNwsFeed(MAPPER.readTree(json)).get(0);
    }

    private PostPriority dispatchedPriority(NormalizedAlert a) {
        snapshot(Set.of("NWS", "USGS", "FEMA"), a);
        assertThat(dispatcher.dispatchOnce()).as("dispatched").isEqualTo(1);
        ArgumentCaptor<Post> post = ArgumentCaptor.forClass(Post.class);
        verify(posts).create(post.capture(), eq(AlertDispatchService.SYSTEM_EMAIL));
        return post.getValue().getPriority();
    }

    @Test
    void aWarningPostIsUrgent() throws Exception {
        NormalizedAlert tornado = nwsWithPolygon("Tornado Warning", "Immediate", "Observed", "Shelter");
        assertThat(dispatcher.matchForAlert(tornado).orElseThrow().tier).isEqualTo("warning");
        assertThat(dispatchedPriority(tornado)).isEqualTo(PostPriority.URGENT);
    }

    @Test
    void aWatchPostIsMedium_neverUrgent() throws Exception {
        NormalizedAlert watch = nwsWithPolygon("Flood Watch", "Future", "Possible", "Prepare");
        assertThat(dispatcher.matchForAlert(watch).orElseThrow().tier).isEqualTo("watch");
        assertThat(dispatchedPriority(watch)).isEqualTo(PostPriority.MEDIUM);
    }

    @Test
    void priorityFallsBackToTheProductName_thenToLow() throws Exception {
        DispatchTemplate untiered = DispatchTemplate.fromJson(MAPPER.readTree("{\"source\":\"NWS\"}"));
        assertThat(AlertDispatchService.autoPostPriority(
                TestAlerts.nws("Wind Advisory").build(), untiered)).isEqualTo(PostPriority.LOW);
        assertThat(AlertDispatchService.autoPostPriority(
                TestAlerts.nws("High Wind Warning").build(), null)).isEqualTo(PostPriority.URGENT);
        assertThat(AlertDispatchService.autoPostPriority(
                TestAlerts.nwsWatch("High Wind Watch").build(), untiered)).isEqualTo(PostPriority.MEDIUM);
        assertThat(AlertDispatchService.autoPostPriority(
                TestAlerts.usgs("M 4.6 - somewhere").build(), null)).isEqualTo(PostPriority.LOW);
        assertThat(AlertDispatchService.autoPostPriority(null, null)).isEqualTo(PostPriority.LOW);
    }

    // ── (f) no SitPrep headline as the official title ────────────────────

    @Test
    void theOfficialTitleNeverFallsBackToTemplateCopy() throws Exception {
        // No template parameter at all now: the official path cannot reach SitPrep copy.
        NormalizedAlert bare = TestAlerts.nws("x").event(null).headline(null).build();

        assertThat(AlertDispatchService.officialTitle(bare)).isEqualTo("Alert nearby");
        assertThat(AlertDispatchService.officialTitle(
                TestAlerts.nws("Tornado Warning").build())).isEqualTo("Tornado Warning");
        assertThat(AlertDispatchService.officialTitle(
                TestAlerts.nws("x").event(" ").headline("Official headline").build()))
                .isEqualTo("Official headline");
    }
}
