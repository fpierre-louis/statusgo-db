package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Feed freshness is honest (Phase 3 D5).
 *
 * <p><b>What was wrong.</b> A source that failed carried its previous alerts
 * forward — correctly — but the tick still built
 * {@code new Snapshot(merged, now, now)}, so {@code lastSuccessAt} read "just
 * now" even when every upstream was down, and {@code meta.isStale} could never
 * trip. Worse, a 200 with no {@code features} array parsed as a legitimate
 * empty feed: the source's alerts vanished, and the resolve tick then cancelled
 * their live posts as "no longer listed".</p>
 *
 * <p>Each tick here is replayed through the real {@code pollAll} against
 * canned bodies at a pinned clock. No network.</p>
 */
class AlertIngestFreshnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant T0 = Instant.parse("2026-10-13T12:00:00Z");
    private static final Instant T1 = T0.plus(Duration.ofMinutes(5));

    private static final String NWS_OK = """
            {"type":"FeatureCollection","features":[{
              "type":"Feature","id":"urn:nws:1",
              "properties":{"id":"urn:nws:1","event":"Tornado Warning","status":"Actual",
                "messageType":"Alert","severity":"Extreme","urgency":"Immediate",
                "certainty":"Observed","headline":"Tornado Warning issued","areaDesc":"Here"},
              "geometry":{"type":"Polygon","coordinates":[[[-97,35],[-96,35],[-96,36],[-97,35]]]}
            }]}""";

    private static final String USGS_OK = """
            {"type":"FeatureCollection","features":[{
              "type":"Feature","id":"us7000abc",
              "properties":{"mag":5.1,"place":"10 km N of Somewhere, CA","time":1760356800000},
              "geometry":{"type":"Point","coordinates":[-118.2,34.0,10.0]}
            }]}""";

    private static final String FEMA_OK = """
            {"DisasterDeclarationsSummaries":[{
              "femaDeclarationString":"DR-9999-OK","disasterNumber":9999,"state":"OK",
              "declarationType":"DR","declarationDate":"2026-09-01T00:00:00.000Z",
              "incidentType":"Severe Storm","declarationTitle":"SEVERE STORMS",
              "designatedArea":"Cleveland (County)","ihProgramDeclared":true
            }]}""";

    /** What upstream returns this tick, keyed NWS / USGS / FEMA. A null body means "throw". */
    private final Map<String, String> bodies = new HashMap<>();
    private final MutableClock clock = new MutableClock(T0);
    private AlertIngestService ingest;

    @BeforeEach
    void setUp() {
        NwsZoneService zones = mock(NwsZoneService.class);
        when(zones.zoneCodesForPoint(anyDouble(), anyDouble())).thenReturn(Set.of());
        ingest = new AlertIngestService(zones);
        ingest.setUpstreamForTest((url, accept) -> {
            String key = url.contains("weather.gov") ? "NWS" : url.contains("usgs.gov") ? "USGS" : "FEMA";
            String body = bodies.get(key);
            if (body == null) throw new RuntimeException("Upstream " + key + " returned HTTP 503");
            return body.isEmpty() ? MissingNode.getInstance() : MAPPER.readTree(body);
        }, clock);
        allOk();
    }

    private void allOk() {
        bodies.put("NWS", NWS_OK);
        bodies.put("USGS", USGS_OK);
        bodies.put("FEMA", FEMA_OK);
    }

    /**
     * One healthy tick at T0, then the next tick at T1 with {@code changes}
     * (source, body pairs) applied to what upstream returns. A null body means
     * "the call throws".
     */
    private Snapshot healthyThenTick(String... changes) {
        ingest.refreshNow();
        for (int i = 0; i < changes.length; i += 2) bodies.put(changes[i], changes[i + 1]);
        clock.set(T1);
        ingest.refreshNow();
        return ingest.getSnapshot();
    }

    private static long count(Snapshot s, String source) {
        return s.alerts().stream().filter(a -> source.equals(a.source())).count();
    }

    // ── (a) per-source success, honest lastSuccessAt ─────────────────────

    @Test
    void aHealthyTickStampsEverySource() {
        ingest.refreshNow();
        Snapshot s = ingest.getSnapshot();

        assertThat(count(s, "NWS")).isEqualTo(1);
        assertThat(count(s, "USGS")).isEqualTo(1);
        assertThat(count(s, "FEMA")).isEqualTo(1);
        assertThat(s.lastSuccessAt()).isEqualTo(T0);
        assertThat(s.sourceLastSuccessAt())
                .containsEntry("NWS", T0).containsEntry("USGS", T0).containsEntry("FEMA", T0);
        assertThat(s.freshSources()).containsExactlyInAnyOrder("NWS", "USGS", "FEMA");
    }

    @Test
    void aFailedNwsPollKeepsItsAlertsButNotItsFreshness() {
        Snapshot s = healthyThenTick("NWS", null);

        assertThat(count(s, "NWS")).as("last-good NWS data is carried forward").isEqualTo(1);
        assertThat(s.lastSuccessAt())
                .as("the feed is only as fresh as its oldest life-safety source")
                .isEqualTo(T0);
        assertThat(s.sourceLastSuccessAt()).containsEntry("NWS", T0).containsEntry("USGS", T1);
        assertThat(s.freshSources()).containsExactlyInAnyOrder("USGS", "FEMA");
        assertThat(s.freshlyPolled("NWS")).isFalse();
        assertThat(s.freshlyPolled("USGS")).isTrue();
    }

    @Test
    void aFailedUsgsPollAlsoAgesTheFeed() {
        Snapshot s = healthyThenTick("USGS", null);

        assertThat(count(s, "USGS")).isEqualTo(1);
        assertThat(s.lastSuccessAt()).isEqualTo(T0);
        assertThat(s.freshlyPolled("USGS")).isFalse();
    }

    @Test
    void aFailedFemaPollDoesNotAgeTheFeed_butKeepsItsOwnTime() {
        Snapshot s = healthyThenTick("FEMA", null);

        assertThat(count(s, "FEMA")).isEqualTo(1);
        assertThat(s.lastSuccessAt())
                .as("FEMA is recovery context, not life-safety — its outage must not stale the feed")
                .isEqualTo(T1);
        assertThat(s.sourceLastSuccessAt()).containsEntry("FEMA", T0);
        assertThat(s.freshlyPolled("FEMA")).isFalse();
    }

    @Test
    void anOutageIsVisibleAsStaleMeta() {
        // Against the real wall clock, because metaFor judges staleness against it.
        Instant now = Instant.now();
        clock.set(now.minus(Duration.ofHours(1)));
        ingest.refreshNow();
        clock.set(now);
        bodies.put("NWS", null);
        ingest.refreshNow();

        AlertFeedService feed = new AlertFeedService(ingest, null);
        assertThat(feed.metaFor(ingest.getSnapshot()).isStale())
                .as("an hour without NWS is stale, whatever the other sources did")
                .isTrue();
    }

    @Test
    void untilEveryLifeSafetySourceHasSucceeded_thereIsNoLastSuccess() {
        bodies.put("NWS", null);
        ingest.refreshNow();
        Snapshot s = ingest.getSnapshot();

        assertThat(s.lastSuccessAt()).isNull();
        assertThat(s.sourceLastSuccessAt()).containsEntry("USGS", T0).doesNotContainKey("NWS");
    }

    @Test
    void aFemaThatNeverAnsweredDoesNotHoldBackLastSuccess() {
        bodies.put("FEMA", null);
        ingest.refreshNow();

        assertThat(ingest.getSnapshot().lastSuccessAt()).isEqualTo(T0);
    }

    // ── (b) a 200 without the expected array is a failure ────────────────

    @Test
    void anNws200WithoutFeaturesIsAFailure_notAnEmptyFeed() {
        Snapshot s = healthyThenTick("NWS", "{\"type\":\"FeatureCollection\",\"title\":\"maintenance\"}");

        assertThat(count(s, "NWS")).as("a malformed 200 must not wipe NWS").isEqualTo(1);
        assertThat(s.sourceLastSuccessAt()).containsEntry("NWS", T0);
        assertThat(s.freshlyPolled("NWS")).isFalse();
        assertThat(s.lastSuccessAt()).isEqualTo(T0);
    }

    @Test
    void anEmptyNwsBodyIsAFailure() {
        Snapshot s = healthyThenTick("NWS", "");   // readTree("") -> MissingNode

        assertThat(count(s, "NWS")).isEqualTo(1);
        assertThat(s.freshlyPolled("NWS")).isFalse();
    }

    @Test
    void aUsgs200WithoutFeaturesIsAFailure() {
        Snapshot s = healthyThenTick("USGS", "{\"type\":\"FeatureCollection\"}");

        assertThat(count(s, "USGS")).isEqualTo(1);
        assertThat(s.sourceLastSuccessAt()).containsEntry("USGS", T0);
        assertThat(s.lastSuccessAt()).isEqualTo(T0);
    }

    @Test
    void aFema200WithoutItsArrayIsAFailure() {
        Snapshot s = healthyThenTick("FEMA", "{\"error\":\"throttled\"}");

        assertThat(count(s, "FEMA")).isEqualTo(1);
        assertThat(s.sourceLastSuccessAt()).containsEntry("FEMA", T0);
        assertThat(s.freshlyPolled("FEMA")).isFalse();
    }

    @Test
    void aPresentButEmptyArrayIsALegitimateEmptyResult() {
        Snapshot s = healthyThenTick(
                "NWS", "{\"type\":\"FeatureCollection\",\"features\":[]}",
                "USGS", "{\"type\":\"FeatureCollection\",\"features\":[]}",
                "FEMA", "{\"DisasterDeclarationsSummaries\":[]}");

        assertThat(s.alerts()).as("every source answered: nothing is active").isEmpty();
        assertThat(s.lastSuccessAt()).isEqualTo(T1);
        assertThat(s.freshSources()).containsExactlyInAnyOrder("NWS", "USGS", "FEMA");
    }

    @Test
    void parseNwsFeedRefusesAShapelessRoot() throws Exception {
        assertThatThrownBy(() -> ingest.parseNwsFeed(MAPPER.readTree("{}")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ingest.parseNwsFeed(null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ingest.parseNwsFeed(MAPPER.readTree("{\"features\":[]}"))).isEmpty();
    }

    // ── the filtered read keeps the freshness it was cut from ────────────

    @Test
    void thePointFilteredSnapshotCarriesPerSourceFreshness() {
        healthyThenTick("NWS", null);

        Snapshot near = ingest.getSnapshotForPoint(35.2, -96.8, 50);
        assertThat(near.lastSuccessAt()).isEqualTo(T0);
        assertThat(near.sourceLastSuccessAt()).containsEntry("NWS", T0).containsEntry("USGS", T1);
        assertThat(near.freshSources()).containsExactlyInAnyOrder("USGS", "FEMA");
    }

    @Test
    void theThreeArgumentSnapshotMeansEverySourceWasFresh() {
        Snapshot s = new Snapshot(java.util.List.<NormalizedAlert>of(), T0, T0);
        assertThat(s.freshSources()).containsExactlyInAnyOrder("NWS", "USGS", "FEMA");
        assertThat(Snapshot.empty().freshSources()).isEmpty();
        assertThat(Snapshot.empty().freshlyPolled("NWS")).isFalse();
    }

    /** A clock the test advances between ticks. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void set(Instant at) { this.now = at; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
