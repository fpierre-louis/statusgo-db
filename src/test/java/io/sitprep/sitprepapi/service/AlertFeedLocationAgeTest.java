package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.constant.LocationFreshness;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit finding <b>F-5</b>, at the wire.
 *
 * <p>The push path has always refused a coordinate older than
 * {@link LocationFreshness}'s window. The pull path could not apply the same
 * rule, because {@code GET /api/alerts/feed} took bare {@code lat}/{@code lng}
 * and the client sent no age. These tests pin the three states the response can
 * now report, and that "we were not told" is one of them rather than a silent
 * pass.</p>
 */
class AlertFeedLocationAgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SAN_CARLOS = Set.of("AZZ560", "AZC007", "AZZ133");
    private static final double LAT = 33.3172, LNG = -110.5297;

    private static AlertFeedService feed;
    private static AlertIngestService ingest;

    @BeforeAll
    static void setUp() throws Exception {
        NwsZoneService zones = new NwsZoneService();
        zones.seedPointZones(LAT, LNG, SAN_CARLOS);
        ingest = new AlertIngestService(zones);
        AlertDispatchService dispatch =
                new AlertDispatchService(null, null, null, null, null, null, zones, null);
        dispatch.loadTemplates();
        feed = new AlertFeedService(ingest, dispatch);

        try (InputStream in = AlertFeedLocationAgeTest.class
                .getResourceAsStream("/fixtures/nws-active-2026-08-22.json")) {
            JsonNode root = MAPPER.readTree(in);
            ingest.setSnapshotForTest(ingest.parseNwsFeed(root));
        }
    }

    @Test
    void aFeedAskedWithNoTimestampReportsUnknownRatherThanFresh() {
        // The pre-fix shape, still permitted: an older client that has not
        // learned to send `fixedAt`. It must not be told its coordinate passed a
        // check nobody ran.
        AlertFeedResponse res = feed.feedFor(LAT, LNG);
        assertThat(res.meta().locationAge())
                .as("null is the third state — not told — and must stay distinguishable")
                .isNull();
    }

    @Test
    void aFreshFixIsReportedCurrentAlongsideTheWindowItPassed() {
        AlertFeedResponse res =
                feed.feedFor(LAT, LNG, Instant.now().minus(Duration.ofMinutes(4)));

        AlertFeedResponse.LocationAge age = res.meta().locationAge();
        assertThat(age).isNotNull();
        assertThat(age.isStale()).isFalse();
        assertThat(age.maxAgeDays())
                .as("the client words the sentence; the server owns the number")
                .isEqualTo(LocationFreshness.maxAgeDays());
    }

    @Test
    void aFixOlderThanThePushWindowIsReportedStale() {
        // The measured case that made this a finding rather than a tidy-up: a
        // coordinate cached in Lehi UT while the user stands in Beaumont TX
        // under a Severe / Immediate Flood Warning. Before this, the feed
        // answered with total confidence about the wrong place.
        AlertFeedResponse res =
                feed.feedFor(LAT, LNG, Instant.now().minus(Duration.ofDays(21)));

        assertThat(res.meta().locationAge()).isNotNull();
        assertThat(res.meta().locationAge().isStale()).isTrue();
    }

    @Test
    void theStaleVerdictUsesTheSAMEWindowThePushPathEnforces() {
        // One boundary, checked from the pull side. A fix one day inside the
        // window passes and one day outside it fails — so the two paths cannot
        // disagree about who is targetable and who is merely being shown things.
        Instant justInside = Instant.now()
                .minus(Duration.ofDays(LocationFreshness.maxAgeDays() - 1));
        Instant justOutside = Instant.now()
                .minus(Duration.ofDays(LocationFreshness.maxAgeDays() + 1));

        assertThat(feed.feedFor(LAT, LNG, justInside).meta().locationAge().isStale()).isFalse();
        assertThat(feed.feedFor(LAT, LNG, justOutside).meta().locationAge().isStale()).isTrue();
    }

    @Test
    void theLocationVerdictIsSeparateFromTheSnapshotVerdict() {
        // `meta.isStale` is about the ALERT DATA, `meta.locationAge.isStale` is
        // about the COORDINATE. The page conflated two clocks once already — it
        // printed the NWS fetch time and called it freshness — so the response
        // must not hand it a single blended answer to do it again with.
        AlertFeedResponse res =
                feed.feedFor(LAT, LNG, Instant.now().minus(Duration.ofDays(21)));

        assertThat(res.meta().locationAge().isStale()).isTrue();
        assertThat(res.meta().isStale())
                .as("the snapshot's own freshness is a different question")
                .isNotNull();
        assertThat(res.meta().coverageCaveat()).isEqualTo(AlertFeedResponse.COVERAGE_CAVEAT);
    }

    @Test
    void theFixTimestampIsEchoedBackSoAClientCanRenderTheAge() {
        Instant fixedAt = Instant.parse("2026-08-20T09:15:00Z");
        AlertFeedResponse res = feed.feedFor(LAT, LNG, fixedAt);
        assertThat(res.meta().locationAge().fixedAt()).isEqualTo(fixedAt.toString());
    }
}
