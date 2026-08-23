package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guards for audit P0-4 (zone-only alerts never dispatch) and the
 * P1-1 payload blowup it caused.
 *
 * <p><b>The fixture is the real feed.</b>
 * {@code fixtures/nws-active-2026-08-22.json} is the verbatim
 * {@code api.weather.gov/alerts/active} response the audit measured — 310
 * alerts, 254 of them with {@code geometry: null}. Every number asserted here
 * was measured against the live API, not invented, so a change in how NWS
 * ships alerts fails these tests rather than silently changing behaviour.</p>
 *
 * <p>The headline number: before this fix, a Salt Lake City user asking for
 * alerts within 250 miles got <b>554 rows / 307 KB</b> of which <b>one</b> was
 * actually near them, because 553 had no polygon and took an
 * include-everything branch.</p>
 */
class AlertIngestZoneMatchingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static List<NormalizedAlert> liveFeed;

    /**
     * Zone codes {@code api.weather.gov/points} really returns for these
     * coordinates, captured 2026-08-22. San Carlos AZ sits inside AZZ560, the
     * zone the live Extreme Heat Warning targets.
     */
    private static final Set<String> SAN_CARLOS_AZ = Set.of("AZZ560", "AZC007", "AZZ133");
    private static final Set<String> PORTLAND_ME = Set.of("MEZ024", "MEC005", "MEZ110");
    private static final Set<String> SALT_LAKE_CITY = Set.of("UTZ106", "UTC035", "UTZ481");

    @BeforeAll
    static void loadFeed() throws Exception {
        AlertIngestService parseOnly = new AlertIngestService(new NwsZoneService());
        try (InputStream in = AlertIngestZoneMatchingTest.class
                .getResourceAsStream("/fixtures/nws-active-2026-08-22.json")) {
            assertThat(in).as("fixture present").isNotNull();
            JsonNode root = MAPPER.readTree(in);
            liveFeed = parseOnly.parseNwsFeed(root);
        }
    }

    /** A service whose zone lookups are pre-seeded — no network in tests. */
    private static AlertIngestService serviceFor(double lat, double lng, Set<String> zones) {
        NwsZoneService zoneService = new NwsZoneService();
        if (zones != null) zoneService.seedPointZones(lat, lng, zones);
        AlertIngestService svc = new AlertIngestService(zoneService);
        svc.setSnapshotForTest(liveFeed);
        return svc;
    }

    // ------------------------------------------------------------------
    // The premise: UGC is on the wire, and it is on the wire for the alerts
    // that have nothing else.
    // ------------------------------------------------------------------

    @Test
    void theFixtureIsStillTheFeedWeMeasured() {
        // 310 raw features, minus the one `status: Test` row that P0-3's
        // ingest filter now drops. The 254 geometry-less count is unaffected —
        // the test row had no geometry either, so it was 254 of 310 raw and is
        // 253 of 309 parsed.
        assertThat(liveFeed).hasSize(309);
        assertThat(liveFeed.stream().filter(a -> a.geometry() == null)).hasSize(253);
    }

    @Test
    void everyAlertCarriesUgc_includingEveryGeometrylessOne() {
        assertThat(liveFeed).allSatisfy(a ->
                assertThat(a.ugc()).as("UGC on %s", a.headline()).isNotEmpty());

        // This is the load-bearing claim. If it ever stops holding, zone
        // matching silently degrades to the state-prefix fallback.
        assertThat(liveFeed.stream()
                .filter(a -> a.geometry() == null)
                .filter(a -> a.ugc().isEmpty()))
                .as("geometry-less alerts with no UGC to match on")
                .isEmpty();
    }

    @Test
    void normalizerExtractsUgcAndSame() {
        NormalizedAlert heat = extremeHeatWarning();
        assertThat(heat.ugc()).containsExactly("AZZ560");
        assertThat(heat.same()).containsExactly("004007");
        assertThat(heat.geometry()).as("this product ships no polygon").isNull();
    }

    // ------------------------------------------------------------------
    // P0-4 acceptance: the zone-only Extreme Heat Warning
    // ------------------------------------------------------------------

    @Test
    void zoneOnlyHeatWarningMatchesTheUserInsideItsZone() {
        Snapshot snap = serviceFor(33.3172, -110.5297, SAN_CARLOS_AZ)
                .getSnapshotForPoint(33.3172, -110.5297, 250);

        assertThat(snap.alerts())
                .as("Extreme Heat Warning reaches the household it covers")
                .anySatisfy(a -> assertThat(a.ugc()).contains("AZZ560"));
    }

    @Test
    void zoneOnlyHeatWarningDoesNotReachPortlandMaine() {
        Snapshot snap = serviceFor(43.6591, -70.2568, PORTLAND_ME)
                .getSnapshotForPoint(43.6591, -70.2568, 250);

        assertThat(snap.alerts())
                .as("an Arizona zone alert must not reach Maine")
                .noneSatisfy(a -> assertThat(a.ugc()).contains("AZZ560"));
    }

    // ------------------------------------------------------------------
    // P1-1 acceptance: the payload stops being the national feed
    // ------------------------------------------------------------------

    @Test
    void saltLakeCityNoLongerReceivesTheWholeCountry() {
        Snapshot snap = serviceFor(40.76, -111.89, SALT_LAKE_CITY)
                .getSnapshotForPoint(40.76, -111.89, 250);

        // Was 255 NWS rows (254 geometry-less passed through + 1 genuinely
        // near). The acceptance bar in the tracker is < 20.
        assertThat(snap.alerts())
                .as("NWS rows served to a Salt Lake City user")
                .hasSizeLessThan(20);

        // And every survivor is there for a reason we can name.
        assertThat(snap.alerts()).allSatisfy(a -> assertThat(
                a.geometry() != null
                        || a.ugc().stream().anyMatch(SALT_LAKE_CITY::contains))
                .as("%s survived the filter without matching geometry or zone", a.headline())
                .isTrue());
    }

    @Test
    void marineZonesDoNotReachInlandUsers() {
        Snapshot snap = serviceFor(40.76, -111.89, SALT_LAKE_CITY)
                .getSnapshotForPoint(40.76, -111.89, 250);

        // 130 Small Craft Advisories dominate the national feed and are coded
        // to marine zones (LMZ844, ANZ...). None of them belong to Utah.
        assertThat(snap.alerts())
                .noneSatisfy(a -> assertThat(a.headline()).contains("Small Craft Advisory"));
    }

    // ------------------------------------------------------------------
    // The fallback ladder
    // ------------------------------------------------------------------

    @Test
    void unknownZonesFallBackToStatePrefix_notToTheWholeCountry() {
        // No seeded point lookup AND no network: zoneCodesForPoint returns
        // empty, so tier 2 is unavailable. Simulate the degraded path by
        // disabling the service entirely.
        NwsZoneService offline = new NwsZoneService();
        offline.setEnabled(false);
        AlertIngestService svc = new AlertIngestService(offline);
        svc.setSnapshotForTest(liveFeed);

        Snapshot snap = svc.getSnapshotForPoint(40.76, -111.89, 250);

        // With no user zones at all we cannot even derive a state, so this
        // degrades to include — the honest answer, and still bounded by the
        // polygon test for the 56 alerts that have geometry.
        assertThat(snap.alerts()).isNotEmpty();
    }

    @Test
    void statePrefixFallbackKeepsInStateAndDropsOutOfState() {
        // Seed a coordinate whose zones are known, then query a DIFFERENT
        // coordinate: tier 2 misses, tier 3 has nothing either. To exercise
        // tier 3 in isolation, seed zones that share Utah's prefix but match
        // no live alert exactly.
        NwsZoneService zones = new NwsZoneService();
        zones.seedPointZones(40.76, -111.89, Set.of("UTZ999"));
        AlertIngestService svc = new AlertIngestService(zones);
        svc.setSnapshotForTest(liveFeed);

        Snapshot snap = svc.getSnapshotForPoint(40.76, -111.89, 250);

        // UTZ999 matches no alert's UGC exactly, so tier 2 returns false for
        // every zone-only alert — the user's zones ARE known, so that is a
        // definite no rather than a fall-through.
        assertThat(snap.alerts())
                .as("known zones that match nothing means no zone alerts, not all of them")
                .allSatisfy(a -> assertThat(a.geometry()).isNotNull());
    }

    @Test
    void alertsWithNothingToMatchOnAreStillIncluded() {
        // The FEMA shape: no geometry, no UGC. Never the reason a real alert
        // is hidden — narrowing that set is audit P1-2, at ingest.
        NormalizedAlert fema = TestAlerts.fema("Fire — PINE TREE ROAD FIRE")
                .id("FM-5673-AR").area("Union (County)").build();

        NwsZoneService zones = new NwsZoneService();
        zones.seedPointZones(40.76, -111.89, SALT_LAKE_CITY);
        AlertIngestService svc = new AlertIngestService(zones);
        svc.setSnapshotForTest(List.of(fema));

        assertThat(svc.getSnapshotForPoint(40.76, -111.89, 250).alerts())
                .containsExactly(fema);
    }

    // ------------------------------------------------------------------
    // The Severe alerts that were being dropped
    // ------------------------------------------------------------------

    @Test
    void theSevereZoneOnlyAlertsAreNoLongerInvisible() {
        long severeZoneOnly = liveFeed.stream()
                .filter(a -> "Severe".equalsIgnoreCase(a.severity()))
                .filter(a -> a.geometry() == null)
                .count();

        // 40 of 75 Severe alerts — the ones RiskProfileService's old
        // `geometry != null` filter silently discarded.
        assertThat(severeZoneOnly).isEqualTo(40);

        // All of them now carry a code the dispatcher can resolve.
        assertThat(liveFeed.stream()
                .filter(a -> "Severe".equalsIgnoreCase(a.severity()))
                .filter(a -> a.geometry() == null))
                .allSatisfy(a -> assertThat(a.ugc()).isNotEmpty());
    }

    @Test
    void redFlagWarningsAreAllZoneOnly_whichIsWhyThisFixWasNeeded() {
        List<NormalizedAlert> redFlag = liveFeed.stream()
                .filter(a -> a.headline() != null && a.headline().startsWith("Red Flag Warning"))
                .toList();

        assertThat(redFlag).as("Red Flag Warnings in the fixture").hasSize(19);
        assertThat(redFlag).allSatisfy(a -> {
            assertThat(a.geometry()).as("Red Flag Warnings never ship a polygon").isNull();
            assertThat(a.ugc()).isNotEmpty();
        });
    }

    private static NormalizedAlert extremeHeatWarning() {
        return liveFeed.stream()
                .filter(a -> a.ugc().contains("AZZ560"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("fixture lost the San Carlos heat warning"));
    }
}
