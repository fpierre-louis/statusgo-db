package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.resource.AppConfigResource;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the alert feed DTO.
 *
 * <p>These enforce the four rules the shape depends on, as tests rather than
 * convention — each corresponds to a way the pipeline has already gone wrong
 * once.</p>
 */
class AlertFeedServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static AlertFeedService feed;
    private static AlertDispatchService dispatch;
    private static AlertIngestService ingest;
    private static NwsZoneService zones;

    /** San Carlos AZ — the zone the live Extreme Heat Warning targets. */
    private static final Set<String> SAN_CARLOS = Set.of("AZZ560", "AZC007", "AZZ133");
    private static final double LAT = 33.3172, LNG = -110.5297;

    @BeforeAll
    static void setUp() throws Exception {
        zones = new NwsZoneService();
        zones.seedPointZones(LAT, LNG, SAN_CARLOS);

        ingest = new AlertIngestService(zones);
        dispatch = new AlertDispatchService(null, null, null, null, null, null, zones, null);
        dispatch.loadTemplates();
        feed = new AlertFeedService(ingest, dispatch);

        try (InputStream in = AlertFeedServiceTest.class
                .getResourceAsStream("/fixtures/nws-active-2026-08-22.json")) {
            JsonNode root = MAPPER.readTree(in);
            ingest.setSnapshotForTest(ingest.parseNwsFeed(root));
        }
    }

    private static AlertCardDto card(NormalizedAlert a, MatchType m) {
        return feed.toCard(a, m, AppConfigResource.alertsRadiusMi());
    }

    // ==================================================================
    // RULE 1 — official.* is never our processed copy
    // ==================================================================

    @Test
    void officialTextIsTheRawWire_notAnythingRoutedThroughSanitizeSlots() {
        String rawWire = "Extreme Heat Warning issued August 22 at 11:42AM MST "
                + "until August 29 at 8:00PM MST by NWS Phoenix AZ";
        NormalizedAlert a = TestAlerts.nws("Extreme Heat Warning")
                .headline(rawWire).description("* WHAT...Dangerously hot conditions.").build();

        AlertCardDto c = card(a, MatchType.ZONE);

        assertThat(c.official().headline()).isEqualTo(rawWire);
        assertThat(c.official().description()).isEqualTo("* WHAT...Dangerously hot conditions.");
        assertThat(c.official().issuedBy()).isEqualTo("NWS Phoenix AZ");

        // Approved SitPrep copy can render, but the official wire text remains
        // separate and the attribution makes the substitution visible.
        assertThat(c.headline()).isEqualTo("Dangerous heat");
        assertThat(c.whatToDo()).contains("cool place");
        assertThat(c.precautions()).isNotEmpty();
        assertThat(c.precautionsSource())
                .contains("SitPrep guidance")
                .contains("not the issuer's wording");
        assertThat(c.safety().guidanceMode()).isEqualTo("supplement_official");
        assertThat(c.safety().reason()).isEqualTo("template_compatible");
    }

    @Test
    void ourCopyIsAbsentRatherThanBorrowedWhenNoTemplateCoversTheProduct() {
        // "Small Craft Advisory" has no template. Presenting wire text as if we
        // had written it is the failure mode; null is the honest answer.
        NormalizedAlert a = TestAlerts.nws("Small Craft Advisory").build();
        AlertCardDto c = card(a, MatchType.ZONE);

        assertThat(c.headline()).isNull();
        assertThat(c.whatToDo()).isNull();
        assertThat(c.official().headline()).isNotNull();
        assertThat(c.eventLabel()).as("still labelled, from the product name")
                .isEqualTo("Small Craft Advisory");
        assertThat(c.safety().guidanceMode()).isEqualTo("no_guidance");
    }

    // ==================================================================
    // RULE 2 — null expiry is distinguishable from expired
    // ==================================================================

    @Test
    void aNullExpiryStaysNull_notEmptyStringAndNotEpochZero() throws Exception {
        NormalizedAlert quake = TestAlerts.usgs("M6.2 — Scotia Sea").endsAt(null).build();
        AlertCardDto c = card(quake, MatchType.POLYGON);

        assertThat(c.expiresAt()).isNull();

        // And it serializes as absent, not as "" or a 1970 timestamp — either
        // would make "we don't know when this ends" read as "this ended".
        String json = MAPPER.writeValueAsString(c);
        assertThat(json).doesNotContain("\"expiresAt\":\"\"");
        assertThat(json).doesNotContain("1970");
    }

    @Test
    void aRealExpiryIsCarriedThroughVerbatim() {
        NormalizedAlert a = TestAlerts.nws("Flood Warning")
                .endsAt("2026-08-27T06:55:00Z").build();
        assertThat(card(a, MatchType.POLYGON).expiresAt()).isEqualTo("2026-08-27T06:55:00Z");
    }

    // ==================================================================
    // RULE 3 — the coverage caveat is one constant, on every response
    // ==================================================================

    @Test
    void everyResponseCarriesTheIdenticalCoverageCaveat() {
        AlertFeedResponse a = feed.feedFor(LAT, LNG);
        AlertFeedResponse b = feed.feedFor(43.6591, -70.2568);   // Portland ME, no alerts

        assertThat(a.meta().coverageCaveat())
                .isSameAs(b.meta().coverageCaveat())
                .isSameAs(AlertFeedResponse.COVERAGE_CAVEAT);
    }

    @Test
    void theCaveatShipsOnANonEmptyFeedToo_notJustTheEmptyState() {
        AlertFeedResponse r = feed.feedFor(LAT, LNG);
        assertThat(r.alerts()).as("San Carlos has a live heat warning").isNotEmpty();
        assertThat(r.meta().coverageCaveat()).isEqualTo(AlertFeedResponse.COVERAGE_CAVEAT);
    }

    @Test
    void theCaveatSaysWhatWeDoAndDoNotPromise() {
        String c = AlertFeedResponse.COVERAGE_CAVEAT;
        assertThat(c).contains("evacuation and shelter-in-place");
        assertThat(c).contains("Coverage varies by jurisdiction");
        assertThat(c).contains("should not be treated as complete");
        assertThat(c).contains("follow instructions from local authorities");
    }

    // ==================================================================
    // RULE 4 — radiusMi is the AppConfig value, not a copy
    // ==================================================================

    @Test
    void radiusMiEqualsTheAppConfigValue() {
        AlertFeedResponse r = feed.feedFor(LAT, LNG);
        assertThat(r.alerts()).isNotEmpty();
        assertThat(r.alerts()).allSatisfy(c ->
                assertThat(c.radiusMi()).isEqualTo(AppConfigResource.alertsRadiusMi()));
    }

    @Test
    void theRadiusIsNotAHandCopiedLiteral() {
        // Guards the specific regression: three layers already disagreed by 50x
        // (250 / 50 / 5) and a literal here would have made four.
        assertThat(card(TestAlerts.nws("Flood Warning").build(), MatchType.POLYGON).radiusMi())
                .isEqualTo(AppConfigResource.alertsRadiusMi());
    }

    // ==================================================================
    // RULE 5 — tier / isLifeThreatening / evacuationRelated pass through
    // ==================================================================

    @Test
    void tierComesFromTheTemplate_notFromParsingTheHeadlineAgain() {
        assertThat(card(TestAlerts.nws("Flood Warning").build(), MatchType.POLYGON).tier())
                .isEqualTo("warning");
        assertThat(card(TestAlerts.nwsWatch("Flood Watch").build(), MatchType.ZONE).tier())
                .isEqualTo("watch");
        // No template: the product-name suffix, a display fallback only.
        assertThat(card(TestAlerts.nws("Heat Advisory").build(), MatchType.ZONE).tier())
                .isEqualTo("advisory");
        assertThat(card(TestAlerts.nws("Special Weather Statement").build(), MatchType.ZONE).tier())
                .isEqualTo("statement");
    }

    @Test
    void aWatchIsNeverLifeThreatening() {
        assertThat(card(TestAlerts.nwsWatch("Flood Watch").build(), MatchType.ZONE)
                .isLifeThreatening()).isFalse();
        assertThat(card(TestAlerts.nws("Flood Warning").build(), MatchType.POLYGON)
                .isLifeThreatening()).isFalse();
        assertThat(card(TestAlerts.nws("Flood Warning")
                        .responseTypes(List.of("Evacuate"))
                        .build(), MatchType.POLYGON)
                .isLifeThreatening()).isFalse();
        assertThat(card(TestAlerts.nws("Flash Flood Warning")
                        .parameters(Map.of("flashFloodDamageThreat", List.of("CATASTROPHIC")))
                        .build(), MatchType.POLYGON)
                .isLifeThreatening()).isTrue();
    }

    @Test
    void aQuakeIsNotLifeThreatening_theShakingAlreadyHappened() {
        // The DTO must agree with the dispatcher, which excludes USGS from
        // push for exactly this reason. It first shipped disagreeing — the
        // mapper had its own copy of the rule that omitted the NWS-only clause.
        NormalizedAlert quake = TestAlerts.usgs("M6.2 — 14 km E of Encinitas, CA")
                .area("14 km E of Encinitas, CA").build();
        assertThat(card(quake, MatchType.POLYGON).isLifeThreatening()).isFalse();
    }

    @Test
    void aRetractedWarningIsNotLifeThreatening() {
        // P0-8 passed through rather than re-derived.
        NormalizedAlert cancelled = TestAlerts.nws("Extreme Heat Warning")
                .headline("The Extreme Heat Warning has been cancelled.")
                .urgency("Past").response("AllClear").build();
        assertThat(card(cancelled, MatchType.ZONE).isLifeThreatening()).isFalse();
    }

    @Test
    void evacuationProductsAreFlagged() {
        assertThat(card(TestAlerts.nws("Evacuation Immediate").build(), MatchType.ZONE)
                .evacuationRelated()).isTrue();
        assertThat(card(TestAlerts.nws("Shelter In Place Warning").build(), MatchType.ZONE)
                .evacuationRelated()).isTrue();
        assertThat(card(TestAlerts.nws("Flood Warning").build(), MatchType.POLYGON)
                .evacuationRelated()).isFalse();
    }

    // ==================================================================
    // Location, meta, detail
    // ==================================================================

    @Test
    void matchTypeIsCarriedSoTheClientCanTellStreetFromState() {
        assertThat(card(TestAlerts.nws("Flood Warning").build(), MatchType.POLYGON)
                .location().matchType()).isEqualTo("polygon");
        assertThat(card(TestAlerts.nws("Flood Warning").build(), MatchType.STATE_PREFIX)
                .location().matchType()).isEqualTo("state_prefix");
        assertThat(card(TestAlerts.fema("Fire — X").build(), MatchType.BROADCAST)
                .location().matchType()).isEqualTo("broadcast");
    }

    @Test
    void aStaleSnapshotIsFlagged_soEmptyAndBrokenDoNotLookAlike() {
        AlertIngestService cold = new AlertIngestService(zones);
        AlertFeedService coldFeed = new AlertFeedService(cold, dispatch);
        // Never polled -> lastSuccessAt null -> stale.
        assertThat(coldFeed.metaFor(AlertIngestService.Snapshot.empty()).isStale()).isTrue();
        assertThat(coldFeed.metaFor(AlertIngestService.Snapshot.empty()).lastSuccessAt()).isNull();

        assertThat(feed.feedFor(LAT, LNG).meta().isStale())
                .as("a snapshot set moments ago is fresh").isFalse();
    }

    @Test
    void quakeDetailCarriesMagnitudeAndDepth() {
        NormalizedAlert quake = TestAlerts.usgs("M6.2 — Scotia Sea")
                .area("Scotia Sea")
                .geometry(Map.of("type", "Point", "coordinates", List.of(-30.0, -60.0, 35.0)))
                .build();
        AlertCardDto.Earthquake eq = card(quake, MatchType.POLYGON).detail().earthquake();

        assertThat(eq.magnitude()).isEqualTo(6.2);
        assertThat(eq.depthKm()).isEqualTo(35.0);
        // pagerLevel + tsunami are still dropped at the normalizer (P1-6) —
        // null rather than invented.
        assertThat(eq.pagerLevel()).isNull();
    }

    @Test
    void attributionIsOnTheCard_notOnlyInTheTermsPage() {
        assertThat(card(TestAlerts.nws("Flood Warning").build(), MatchType.POLYGON)
                .sourceAttribution()).isEqualTo("National Weather Service (NOAA)");
        assertThat(card(TestAlerts.usgs("M6.2 — x").build(), MatchType.POLYGON)
                .sourceAttribution()).isEqualTo("U.S. Geological Survey");
    }

    @Test
    void sourceIsLowercasedForTheWireContract() {
        assertThat(card(TestAlerts.nws("Flood Warning").build(), MatchType.POLYGON).source())
                .isEqualTo("nws");
        assertThat(card(TestAlerts.usgs("M6.2 — x").build(), MatchType.POLYGON).source())
                .isEqualTo("usgs");
    }

    @Test
    void theFeedIsScopedToThePoint_notTheNation() {
        assertThat(feed.feedFor(LAT, LNG).alerts()).hasSizeLessThan(20);
        assertThat(feed.feedFor(43.6591, -70.2568).alerts()).isEmpty();
    }
}
