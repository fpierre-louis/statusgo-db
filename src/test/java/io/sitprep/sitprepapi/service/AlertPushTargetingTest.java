package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.GeoUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Push-targeting guards (audit P1-3).
 *
 * <h2>Why this is load-bearing for P0-5</h2>
 *
 * <p>P0-5 dropped {@code {distance}} / {@code {direction}} from push copy and
 * replaced them with {@code {place}}. That argument rests on the recipient
 * already being correctly geo-gated: <i>you got this push because you are in
 * range, so the alert's own location is context enough.</i></p>
 *
 * <p><b>That assumption was false while targeting used the first polygon
 * vertex.</b> An 80 km circle centred on an arbitrary corner of a
 * county-spanning warning reaches people outside the warning and misses people
 * inside it — so "if you got this, it's near you" was true in intent and only
 * roughly true in fact. {@link #coherence_aRecipientWhoGetsThePushIsInsideTheWarning()}
 * is the test that ties the two together rather than checking centroid maths
 * and {@code {place}} substitution in isolation.</p>
 */
class AlertPushTargetingTest {

    private static AlertDispatchService dispatcher;

    @BeforeAll
    static void setUp() {
        dispatcher = new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatcher.loadTemplates();
    }

    /**
     * A long east-west warning polygon, roughly Oklahoma County westward.
     * First vertex sits at the far west end; the population it warns is spread
     * across the whole span.
     */
    private static Map<String, Object> longPolygon() {
        return Map.of(
                "type", "Polygon",
                "coordinates", List.of(List.of(
                        List.of(-99.0, 35.0),   // far west — the old anchor
                        List.of(-97.0, 35.0),
                        List.of(-97.0, 35.4),
                        List.of(-99.0, 35.4),
                        List.of(-99.0, 35.0))));
    }

    // ==================================================================
    // Centroid, not corner
    // ==================================================================

    @Test
    void centroidIsTheMeanOfTheVertices_notTheFirstOne() {
        double[] c = AlertDispatchService.centroidOfGeometry(longPolygon());

        assertThat(c).isNotNull();
        assertThat(c[0]).as("lon").isCloseTo(-98.0, within(0.01));
        assertThat(c[1]).as("lat").isCloseTo(35.2, within(0.01));

        // The old anchor was the first vertex. Distance between the two is the
        // size of the targeting error this fixes.
        double errorKm = GeoUtil.haversineKm(35.0, -99.0, c[1], c[0]);
        assertThat(errorKm)
                .as("first-vertex vs centroid offset on a realistic polygon")
                .isGreaterThan(80.0);
    }

    @Test
    void aRingsClosingVertexIsNotDoubleCounted() {
        // GeoJSON repeats a ring's first position as its last. Counting it
        // twice drags the centroid toward that corner — a fifth of the
        // polygon's width on a 5-point box, which is the shape most
        // hand-drawn warnings are.
        double[] c = AlertDispatchService.centroidOfGeometry(longPolygon());
        assertThat(c[0]).as("lon, closing vertex excluded").isCloseTo(-98.0, within(1e-9));

        // Same box without the explicit closing vertex — must agree.
        double[] open = AlertDispatchService.centroidOfGeometry(Map.of(
                "type", "Polygon",
                "coordinates", List.of(List.of(
                        List.of(-99.0, 35.0), List.of(-97.0, 35.0),
                        List.of(-97.0, 35.4), List.of(-99.0, 35.4)))));
        assertThat(open).containsExactly(c[0], c[1]);
    }

    @Test
    void knownLimitation_aFixedRadiusStillUnderCoversAVeryLargePolygon() {
        // Honest about what centroid targeting does NOT fix. The push radius is
        // a fixed 80 km; this polygon is ~180 km wide, so someone at its far
        // edge is ~90 km from the centre and still misses out.
        //
        // Centroid targeting halves the worst-case error (corner → centre) but
        // does not eliminate it. The real fix is per-recipient point-in-polygon
        // rather than a circle, which is a larger change than P1-3 scoped.
        double[] c = AlertDispatchService.centroidOfGeometry(longPolygon());
        double farEdgeKm = GeoUtil.haversineKm(35.2, -97.0, c[1], c[0]);

        assertThat(farEdgeKm)
                .as("a recipient at the polygon's east edge is still outside the 80 km circle")
                .isGreaterThan(80.0);
    }

    @Test
    void centroidHandlesMultiPolygonAndPoint() {
        assertThat(AlertDispatchService.centroidOfGeometry(Map.of(
                "type", "Point", "coordinates", List.of(-110.5, 33.3))))
                .containsExactly(-110.5, 33.3);

        double[] multi = AlertDispatchService.centroidOfGeometry(Map.of(
                "type", "MultiPolygon",
                "coordinates", List.of(
                        List.of(List.of(List.of(-100.0, 30.0), List.of(-100.0, 32.0))),
                        List.of(List.of(List.of(-104.0, 30.0), List.of(-104.0, 32.0))))));
        assertThat(multi[0]).isCloseTo(-102.0, within(1e-9));
        assertThat(multi[1]).isCloseTo(31.0, within(1e-9));
    }

    @Test
    void unusableGeometryYieldsNullRatherThanThrowing() {
        assertThat(AlertDispatchService.centroidOfGeometry(null)).isNull();
        assertThat(AlertDispatchService.centroidOfGeometry("not a map")).isNull();
        assertThat(AlertDispatchService.centroidOfGeometry(Map.of("type", "Polygon"))).isNull();
        assertThat(AlertDispatchService.centroidOfGeometry(
                Map.of("type", "Polygon", "coordinates", List.of()))).isNull();
    }

    // ==================================================================
    // The coherence test — targeting and copy checked together
    // ==================================================================

    @Test
    void coherence_aRecipientWhoGetsThePushIsInsideTheWarning() {
        // A real Severe Thunderstorm Warning over the long polygon.
        NormalizedAlert alert = TestAlerts.nws("Severe Thunderstorm Warning")
                .geometry(longPolygon())
                .area("Oklahoma, Canadian, Caddo counties")
                .build();

        double[] target = AlertDispatchService.centroidOfGeometry(alert.geometry());
        double centreLat = target[1], centreLon = target[0];

        // The push radius the dispatcher uses.
        final double pushRadiusKm = 80.0;

        // Someone at the eastern end of the warning — inside the polygon, and
        // ~91 km from the FIRST VERTEX, so the old targeting missed them.
        double eastLat = 35.2, eastLon = -97.2;
        assertThat(GeoUtil.haversineKm(eastLat, eastLon, 35.0, -99.0))
                .as("distance from the OLD anchor — outside the 80 km circle")
                .isGreaterThan(pushRadiusKm);
        assertThat(GeoUtil.haversineKm(eastLat, eastLon, centreLat, centreLon))
                .as("distance from the centroid — inside the circle, so they now get it")
                .isLessThan(pushRadiusKm);

        // And the copy they receive makes sense without restating their own
        // distance — which is the P0-5 claim this test exists to back.
        DispatchTemplate tpl = dispatcher.matchForAlert(alert).orElseThrow();
        String body = AlertDispatchService.fillBody(tpl, alert);

        assertThat(body).doesNotContain("{");
        assertThat(body).isEqualTo(
                "Damaging wind and hail are moving in. Go inside now, stay off the road, "
                        + "and keep away from windows.");
        assertThat(tpl.isWarningTier()).as("and it is push-worthy at all").isTrue();
    }

    @Test
    void coherence_aQuakePushNamesAPlaceRatherThanTheRecipientsDistance() {
        // The USGS case, where {place} carries the location instead of a
        // per-recipient distance the batched multicast cannot personalise.
        NormalizedAlert quake = TestAlerts.usgs("M5.9 — 14 km E of Encinitas, CA")
                .area("14 km E of Encinitas, CA")
                .geometry(Map.of("type", "Point", "coordinates", List.of(-117.1, 33.0)))
                .build();

        double[] target = AlertDispatchService.centroidOfGeometry(quake.geometry());
        assertThat(target).as("a Point's centroid is the point itself")
                .containsExactly(-117.1, 33.0);

        String body = AlertDispatchService.fillBody(
                dispatcher.matchForAlert(quake).orElseThrow(), quake);

        // Every recipient of this batch is within 80 km of the epicentre, and
        // the body names where it was — no recipient-specific number needed.
        assertThat(body).isEqualTo(
                "Magnitude 5.9 earthquake — 14 km E of Encinitas, CA. "
                        + "Check your home for damage. Smell for gas and look for water leaks.");
    }

    // ==================================================================
    // Zone-only alerts still target via the P0-4 centroid
    // ==================================================================

    @Test
    void zoneOnlyAlertsStillResolveThroughTheZoneCentroid() {
        // P0-4 handles the no-polygon case; P1-3 must not have broken it.
        NwsZoneService zones = new NwsZoneService();
        zones.seedCentroid("AZZ560", 33.3172, -110.5297);

        AlertDispatchService svc =
                new AlertDispatchService(null, null, null, null, null, null, zones, null);
        NormalizedAlert heat = TestAlerts.nws("Extreme Heat Warning")
                .geometry(null).ugc(List.of("AZZ560")).build();

        assertThat(zones.centroidForZone("AZZ560")).isPresent();
        assertThat(heat.geometry()).isNull();
        assertThat(svc).isNotNull();
    }
}
