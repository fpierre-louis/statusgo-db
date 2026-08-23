package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure-function guards for the zone resolver (audit P0-4). The network paths
 * are covered indirectly by {@link AlertIngestZoneMatchingTest} through the
 * seeded caches; what needs pinning here is the parsing, because every one of
 * these was derived from a live response shape that could change.
 */
class NwsZoneServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // UGC extraction from the zone URLs /points returns
    // ------------------------------------------------------------------

    @Test
    void extractsUgcFromEachZoneUrlShape() {
        // Verbatim from api.weather.gov/points/33.3,-110.4 on 2026-08-22.
        assertThat(NwsZoneService.ugcFromZoneUrl("https://api.weather.gov/zones/forecast/AZZ509"))
                .isEqualTo("AZZ509");
        assertThat(NwsZoneService.ugcFromZoneUrl("https://api.weather.gov/zones/county/AZC009"))
                .isEqualTo("AZC009");
        assertThat(NwsZoneService.ugcFromZoneUrl("https://api.weather.gov/zones/fire/AZZ152"))
                .isEqualTo("AZZ152");
    }

    @Test
    void ugcExtractionIsNullSafeAndTrailingSlashSafe() {
        assertThat(NwsZoneService.ugcFromZoneUrl(null)).isNull();
        assertThat(NwsZoneService.ugcFromZoneUrl("")).isNull();
        assertThat(NwsZoneService.ugcFromZoneUrl("https://api.weather.gov/zones/forecast/")).isNull();
        assertThat(NwsZoneService.ugcFromZoneUrl("no-slashes")).isNull();
    }

    // ------------------------------------------------------------------
    // Zone kind, which decides the endpoint
    // ------------------------------------------------------------------

    @Test
    void zoneTypeIsDerivedFromTheThirdCharacter() {
        assertThat(NwsZoneService.zoneTypeFor("AZC007")).isEqualTo("county");
        assertThat(NwsZoneService.zoneTypeFor("AZZ560")).isEqualTo("forecast");
        // Fire zones share the Z letter with public zones — ORZ691 is a fire
        // zone and still resolves through the forecast endpoint.
        assertThat(NwsZoneService.zoneTypeFor("ORZ691")).isEqualTo("forecast");
    }

    @Test
    void unrecognizedZoneCodesYieldNoType() {
        assertThat(NwsZoneService.zoneTypeFor(null)).isNull();
        assertThat(NwsZoneService.zoneTypeFor("AZ")).isNull();
        assertThat(NwsZoneService.zoneTypeFor("AZX001")).isNull();
    }

    // ------------------------------------------------------------------
    // Centroid — walks arbitrarily nested GeoJSON coordinate arrays
    // ------------------------------------------------------------------

    @Test
    void centroidOfPolygonAveragesItsVertices() throws Exception {
        var geom = MAPPER.readTree("""
                {"type":"Polygon","coordinates":[[[-110.0,33.0],[-111.0,33.0],[-111.0,34.0],[-110.0,34.0]]]}
                """);
        double[] c = NwsZoneService.centroidOf(geom);
        assertThat(c).isNotNull();
        assertThat(c[0]).as("lat").isCloseTo(33.5, within(1e-9));
        assertThat(c[1]).as("lng").isCloseTo(-110.5, within(1e-9));
    }

    @Test
    void centroidHandlesMultiPolygon() throws Exception {
        var geom = MAPPER.readTree("""
                {"type":"MultiPolygon","coordinates":[
                  [[[-110.0,33.0],[-110.0,35.0]]],
                  [[[-114.0,33.0],[-114.0,35.0]]]]}
                """);
        double[] c = NwsZoneService.centroidOf(geom);
        assertThat(c[0]).as("lat").isCloseTo(34.0, within(1e-9));
        assertThat(c[1]).as("lng").isCloseTo(-112.0, within(1e-9));
    }

    @Test
    void centroidReturnsNullRatherThanThrowingOnUnusableGeometry() throws Exception {
        assertThat(NwsZoneService.centroidOf(null)).isNull();
        assertThat(NwsZoneService.centroidOf(MAPPER.readTree("{}"))).isNull();
        assertThat(NwsZoneService.centroidOf(MAPPER.readTree("{\"type\":\"Polygon\"}"))).isNull();
        assertThat(NwsZoneService.centroidOf(
                MAPPER.readTree("{\"type\":\"Polygon\",\"coordinates\":[]}"))).isNull();
    }

    // ------------------------------------------------------------------
    // Cache behaviour the dispatch path depends on
    // ------------------------------------------------------------------

    @Test
    void centroidLookupIsCacheOnly_andDoesNotBlockOnAMiss() {
        NwsZoneService svc = new NwsZoneService();
        svc.setEnabled(false);   // guarantee no network even if something changed

        // A zone that was never warmed returns empty immediately rather than
        // fetching — dispatch runs inside a transaction and must not block.
        assertThat(svc.centroidForZone("AZZ560")).isEmpty();

        svc.seedCentroid("AZZ560", 33.3172, -110.5297);
        assertThat(svc.centroidForZone("AZZ560"))
                .hasValueSatisfying(c -> {
                    assertThat(c[0]).isCloseTo(33.3172, within(1e-6));
                    assertThat(c[1]).isCloseTo(-110.5297, within(1e-6));
                });
    }

    @Test
    void centroidLookupIsCaseInsensitiveAndNullSafe() {
        NwsZoneService svc = new NwsZoneService();
        svc.seedCentroid("azz560", 33.3, -110.5);
        assertThat(svc.centroidForZone("AZZ560")).isPresent();
        assertThat(svc.centroidForZone(null)).isEmpty();
    }

    @Test
    void disabledServiceReportsNoZonesRatherThanReachingOut() {
        NwsZoneService svc = new NwsZoneService();
        svc.setEnabled(false);
        assertThat(svc.zoneCodesForPoint(33.3172, -110.5297)).isEmpty();
        // warmZones must also be inert, not just quiet.
        svc.warmZones(Set.of("AZZ560"));
        assertThat(svc.centroidForZone("AZZ560")).isEmpty();
    }

    @Test
    void seededPointZonesAreServedFromCache() {
        NwsZoneService svc = new NwsZoneService();
        svc.seedPointZones(33.3172, -110.5297, Set.of("AZZ560", "AZC007", "AZZ133"));
        assertThat(svc.zoneCodesForPoint(33.3172, -110.5297))
                .containsExactlyInAnyOrder("AZZ560", "AZC007", "AZZ133");

        // A coordinate inside the same ~110 m bucket shares the entry.
        assertThat(svc.zoneCodesForPoint(33.31719, -110.52971)).isNotEmpty();
    }
}
