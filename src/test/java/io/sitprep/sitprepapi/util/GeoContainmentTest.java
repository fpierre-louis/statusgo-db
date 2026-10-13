package io.sitprep.sitprepapi.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.util.GeoContainment.Containment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.sitprep.sitprepapi.util.GeoContainment.Containment.INSIDE;
import static io.sitprep.sitprepapi.util.GeoContainment.Containment.OUTSIDE;
import static io.sitprep.sitprepapi.util.GeoContainment.Containment.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeoContainment} against real NWS polygons.
 *
 * <p>Fixtures under {@code src/test/resources/geo/} are single NWS features,
 * values verbatim (re-indented only):</p>
 * <ul>
 *   <li>{@code nws-severe-tstorm-warning-washington-oh-2026-08-22.json} — from
 *       the recorded {@code fixtures/nws-active-2026-08-22.json} feed. Twelve
 *       positions with a notch cut into its north edge right next to the first
 *       vertex, which is what makes it the useful one for the R4 defect: a
 *       point in the notch is a few km from the first vertex and still outside.</li>
 *   <li>{@code nws-flood-warning-okaloosa-fl-2026-10-13.json} — fetched live
 *       from {@code api.weather.gov/alerts/active} on 2026-10-13. A thin
 *       sliver with a westward tail, so its bounding box is mostly outside it.</li>
 * </ul>
 *
 * <p>Neither real feed has a hole or a MultiPolygon (all 58 polygons checked
 * are single-ring {@code Polygon}s), so those cases use constructed shapes.</p>
 *
 * <p>Points are written {@code (lat, lng)}, as the method takes them; GeoJSON
 * positions are {@code [lng, lat]}.</p>
 */
class GeoContainmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The feed radius {@code matchTypeFor} applies to the first vertex today. */
    private static final double FIFTY_MILES_KM = GeoUtil.milesToKm(50);

    /** Geometry maps, in the exact shape ingest holds them ({@code json.convertValue(node, Map.class)}). */
    private static Map<String, Object> ohio;
    private static Map<String, Object> okaloosa;
    private static JsonNode ohioFeature;

    @BeforeAll
    static void loadFixtures() throws Exception {
        ohioFeature = read("/geo/nws-severe-tstorm-warning-washington-oh-2026-08-22.json");
        ohio = geometryOf(ohioFeature);
        okaloosa = geometryOf(read("/geo/nws-flood-warning-okaloosa-fl-2026-10-13.json"));
    }

    // ------------------------------------------------------- real NWS shapes

    @Test
    void clearlyInsideAndClearlyOutsideARealWarningPolygon() {
        assertThat(GeoContainment.locate(39.45, -81.40, ohio)).isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(39.00, -81.40, ohio)).as("well south").isEqualTo(OUTSIDE);
        assertThat(GeoContainment.locate(34.05, -118.24, ohio)).as("Los Angeles").isEqualTo(OUTSIDE);

        assertThat(GeoContainment.locate(30.695, -86.58, okaloosa)).as("in the western tail").isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(30.74, -86.515, okaloosa)).as("in the sliver").isEqualTo(INSIDE);
    }

    /**
     * The defect this class exists for (R4): {@code matchTypeFor} says POLYGON
     * whenever the first vertex is within 50 mi. Each point here passes that
     * test and is outside the polygon.
     */
    @Test
    void nearTheFirstVertexButOutsideIsOutside() {
        double[] first = firstVertex(ohio);   // {lng, lat}
        assertThat(first).containsExactly(-81.26, 39.6);

        // In the notch: ~4 km from the first vertex, inside the bounding box.
        assertOutsideButNearFirstVertex(ohio, 39.595, -81.31);
        // ~37 mi due north: a household the feed calls "polygon" today.
        assertOutsideButNearFirstVertex(ohio, 40.13, -81.26);

        // Okaloosa: inside its bounding box, beside the sliver.
        assertOutsideButNearFirstVertex(okaloosa, 30.74, -86.60);
        // Okaloosa: just east of the first vertex.
        assertOutsideButNearFirstVertex(okaloosa, 30.74, -86.45);
    }

    @Test
    void aPointOnAnEdgeOrVertexIsInside() {
        // Midpoint of the horizontal notch floor (-81.35,39.59)-(-81.28,39.59).
        assertThat(GeoContainment.locate(39.59, -81.315, ohio)).isEqualTo(INSIDE);
        // Midpoint of the slanted south edge (-81.17,39.37)-(-81.64,39.34).
        assertThat(GeoContainment.locate(39.355, -81.405, ohio)).isEqualTo(INSIDE);
        // A vertex, and the first vertex itself.
        assertThat(GeoContainment.locate(39.34, -81.64, ohio)).isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(39.6, -81.26, ohio)).isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(30.78, -86.5, okaloosa)).isEqualTo(INSIDE);
    }

    @Test
    void theBoundaryToleranceIsNotABuffer() {
        // One micro-degree (~11 cm) either side of the notch floor at lat 39.59.
        assertThat(GeoContainment.locate(39.59 + 1e-6, -81.315, ohio)).as("above: in the notch").isEqualTo(OUTSIDE);
        assertThat(GeoContainment.locate(39.59 - 1e-6, -81.315, ohio)).as("below: in the polygon").isEqualTo(INSIDE);
    }

    @Test
    void positionsAreReadAsLngLat() {
        // A square at lng 10..11, lat 50..51 (central Germany).
        Map<String, Object> square = polygon(ring(10, 50, 11, 51));
        assertThat(GeoContainment.locate(50.5, 10.5, square)).isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(10.5, 50.5, square)).as("point given lng-first").isEqualTo(OUTSIDE);

        // The real fixture, point swapped: a valid coordinate, just nowhere near Ohio.
        assertThat(GeoContainment.locate(-81.40, 39.45, ohio)).isEqualTo(OUTSIDE);
        // Swapped where the "latitude" is out of range is caught, not guessed.
        assertThat(GeoContainment.locate(-118.24, 34.05, ohio)).isEqualTo(UNKNOWN);
    }

    @Test
    void mapJsonNodeAndTextAllGiveTheSameAnswer() throws Exception {
        JsonNode node = ohioFeature.get("geometry");
        String text = JSON.writeValueAsString(node);
        for (double[] p : new double[][] { {39.45, -81.40}, {39.595, -81.31}, {39.59, -81.315} }) {
            Containment fromMap = GeoContainment.locate(p[0], p[1], ohio);
            assertThat(GeoContainment.locate(p[0], p[1], node)).isEqualTo(fromMap);
            assertThat(GeoContainment.locateJson(p[0], p[1], text)).isEqualTo(fromMap);
            assertThat(GeoContainment.locate(p[0], p[1], (Object) text)).isEqualTo(fromMap);
        }
    }

    /**
     * Every polygon in the recorded 2026-08-22 feed reads as a valid shape: its
     * first vertex is INSIDE (on the boundary) and a far point is OUTSIDE. None
     * is UNKNOWN, so the malformed-geometry rules don't reject real NWS data.
     */
    @Test
    void everyPolygonInTheRecordedFeedIsReadable() throws Exception {
        JsonNode feed = read("/fixtures/nws-active-2026-08-22.json");
        int polygons = 0;
        for (JsonNode f : feed.path("features")) {
            JsonNode g = f.path("geometry");
            if (g.isMissingNode() || g.isNull()) continue;
            polygons++;
            Map<String, Object> geom = geometryOf(f);
            double[] v = firstVertex(geom);
            assertThat(GeoContainment.locate(v[1], v[0], geom)).as(f.path("id").asText()).isEqualTo(INSIDE);
            assertThat(GeoContainment.locate(0.0, 0.0, geom)).as(f.path("id").asText()).isEqualTo(OUTSIDE);
        }
        assertThat(polygons).isEqualTo(56);
    }

    // ------------------------------------------------- holes, multipolygons

    @Test
    void aHoleExcludesButItsEdgeIsStillBoundary() {
        Map<String, Object> holed = polygon(ring(-111, 40, -110, 41), ring(-110.6, 40.4, -110.4, 40.6));
        assertThat(GeoContainment.locate(40.2, -110.8, holed)).as("between shell and hole").isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(40.5, -110.5, holed)).as("in the hole").isEqualTo(OUTSIDE);
        assertThat(GeoContainment.locate(40.4, -110.5, holed)).as("on the hole's edge").isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(40.6, -110.4, holed)).as("on a hole vertex").isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(41.5, -110.5, holed)).as("past the shell").isEqualTo(OUTSIDE);
    }

    @Test
    void aMultiPolygonIsInsideAnyMemberRespectingThatMembersHoles() {
        Map<String, Object> multi = Map.of("type", "MultiPolygon", "coordinates", List.of(
                List.of(ring(-111, 40, -110, 41), ring(-110.6, 40.4, -110.4, 40.6)),
                List.of(ring(-100, 30, -99, 31))));
        assertThat(GeoContainment.locate(40.2, -110.8, multi)).isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(30.5, -99.5, multi)).as("second member").isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(40.5, -110.5, multi)).as("first member's hole").isEqualTo(OUTSIDE);
        assertThat(GeoContainment.locate(35.0, -105.0, multi)).as("between members").isEqualTo(OUTSIDE);

        // Members are a union: a second member covering the first member's hole wins.
        Map<String, Object> filled = Map.of("type", "MultiPolygon", "coordinates", List.of(
                List.of(ring(-111, 40, -110, 41), ring(-110.6, 40.4, -110.4, 40.6)),
                List.of(ring(-110.55, 40.45, -110.45, 40.55))));
        assertThat(GeoContainment.locate(40.5, -110.5, filled)).isEqualTo(INSIDE);

        // Both real fixtures as one MultiPolygon.
        Map<String, Object> both = Map.of("type", "MultiPolygon",
                "coordinates", List.of(ohio.get("coordinates"), okaloosa.get("coordinates")));
        assertThat(GeoContainment.locate(39.45, -81.40, both)).isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(30.695, -86.58, both)).isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(39.595, -81.31, both)).isEqualTo(OUTSIDE);
    }

    @Test
    void aMalformedMemberMakesTheRestUnknownUnlessAValidMemberCovers() {
        List<List<Double>> unclosed = List.of(List.of(-100.0, 30.0), List.of(-99.0, 30.0),
                List.of(-99.0, 31.0), List.of(-100.0, 31.0));
        Map<String, Object> multi = Map.of("type", "MultiPolygon", "coordinates", List.of(
                List.of(ring(-111, 40, -110, 41)),
                List.of(unclosed)));
        assertThat(GeoContainment.locate(40.5, -110.5, multi)).as("covered by the valid member").isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(30.5, -99.5, multi)).as("where the unreadable one was").isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(0.0, 0.0, multi)).as("nowhere near either").isEqualTo(UNKNOWN);
    }

    // ------------------------------------------------------- antimeridian

    @Test
    void anUnsplitAntimeridianRingIsUnknownAndASplitOneWorks() {
        // A box from 179°E to 179°W, written the way RFC 7946 says not to.
        Map<String, Object> unsplit = polygon(List.of(
                List.of(179.0, 51.0), List.of(-179.0, 51.0), List.of(-179.0, 52.0),
                List.of(179.0, 52.0), List.of(179.0, 51.0)));
        assertThat(GeoContainment.locate(51.5, 179.5, unsplit)).isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(51.5, 0.0, unsplit)).as("not 'outside' the wrong way round").isEqualTo(UNKNOWN);

        // The same box split at the line, as RFC 7946 §3.1.9 asks.
        Map<String, Object> split = Map.of("type", "MultiPolygon", "coordinates", List.of(
                List.of(ring(179, 51, 180, 52)),
                List.of(ring(-180, 51, -179, 52))));
        assertThat(GeoContainment.locate(51.5, 179.5, split)).isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(51.5, -179.5, split)).isEqualTo(INSIDE);
        assertThat(GeoContainment.locate(51.5, 0.0, split)).isEqualTo(OUTSIDE);
    }

    // ----------------------------------------------- malformed -> UNKNOWN

    @Test
    void missingOrNonAreaGeometryIsUnknown() {
        assertThat(GeoContainment.locate(39.45, -81.40, (Object) null)).isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(39.45, -81.40, (JsonNode) null)).isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locateJson(39.45, -81.40, null)).isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locateJson(39.45, -81.40, "")).isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locateJson(39.45, -81.40, "POLYGON((...))")).isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(39.45, -81.40, Map.of())).isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(39.45, -81.40, List.of())).isEqualTo(UNKNOWN);

        assertThat(GeoContainment.locate(39.45, -81.40,
                Map.of("type", "Point", "coordinates", List.of(-81.40, 39.45))))
                .as("a quake epicentre has no inside, even at the epicentre").isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(39.45, -81.40,
                Map.of("type", "LineString", "coordinates", List.of(List.of(-82.0, 39.0), List.of(-81.0, 40.0)))))
                .isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(39.45, -81.40,
                Map.of("type", "GeometryCollection", "geometries", List.of(ohio))))
                .isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(39.45, -81.40, ohioFeature))
                .as("a whole Feature, not its geometry").isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(39.45, -81.40, Map.of("coordinates", ohio.get("coordinates"))))
                .as("no type").isEqualTo(UNKNOWN);
    }

    @Test
    void malformedCoordinatesAreUnknownNeverOutside() {
        // Empty and wrong-shaped coordinate arrays.
        assertThat(locatePolygonCoords(List.of())).as("no rings").isEqualTo(UNKNOWN);
        assertThat(locatePolygonCoords("x")).as("coordinates not a list").isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(40.5, -110.5, Map.of("type", "MultiPolygon", "coordinates", List.of())))
                .as("no members").isEqualTo(UNKNOWN);
        assertThat(locatePolygonCoords(List.of(List.of()))).as("empty ring").isEqualTo(UNKNOWN);

        // Fewer than four positions (a closed triangle needs four).
        assertThat(locatePolygonCoords(List.of(List.of(
                List.of(-111.0, 40.0), List.of(-110.0, 41.0), List.of(-111.0, 40.0)))))
                .isEqualTo(UNKNOWN);

        // Unclosed: last position does not repeat the first.
        assertThat(locatePolygonCoords(List.of(List.of(
                List.of(-111.0, 40.0), List.of(-110.0, 40.0), List.of(-110.0, 41.0), List.of(-111.0, 41.0)))))
                .isEqualTo(UNKNOWN);

        // Non-numeric, non-finite, short and out-of-range positions.
        assertThat(locatePolygonCoords(List.of(withVertex(1, List.of("-110.0", "40.0"))))).isEqualTo(UNKNOWN);
        assertThat(locatePolygonCoords(List.of(withVertex(1, List.of(Double.NaN, 40.0))))).isEqualTo(UNKNOWN);
        assertThat(locatePolygonCoords(List.of(withVertex(1, List.of(-110.0))))).isEqualTo(UNKNOWN);
        assertThat(locatePolygonCoords(List.of(withVertex(1, List.of(-110.0, 95.0))))).isEqualTo(UNKNOWN);
        assertThat(locatePolygonCoords(List.of(withVertex(1, "not a position")))).isEqualTo(UNKNOWN);

        // Zero area: every vertex on one line.
        assertThat(locatePolygonCoords(List.of(List.of(
                List.of(-111.0, 40.0), List.of(-110.5, 40.5), List.of(-110.0, 41.0), List.of(-111.0, 40.0)))))
                .isEqualTo(UNKNOWN);

        // An altitude is allowed (RFC 7946 §3.1.1) and ignored.
        List<List<Double>> withAlt = new ArrayList<>();
        for (List<Double> v : ring(-111, 40, -110, 41)) withAlt.add(List.of(v.get(0), v.get(1), 12.0));
        assertThat(locatePolygonCoords(List.of(withAlt))).isEqualTo(INSIDE);
    }

    @Test
    void aMalformedHoleIsNotDroppedSilently() {
        // If the bad hole were skipped, the point in it would read INSIDE.
        List<List<Double>> badHole = List.of(List.of(-110.6, 40.4), List.of(-110.4, 40.4),
                List.of(-110.4, 40.6), List.of(-110.6, 40.6));   // unclosed
        Map<String, Object> holed = polygon(ring(-111, 40, -110, 41), badHole);
        assertThat(GeoContainment.locate(40.5, -110.5, holed)).isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(40.2, -110.8, holed)).isEqualTo(UNKNOWN);
    }

    @Test
    void anUnreadablePointIsUnknown() {
        assertThat(GeoContainment.locate(Double.NaN, -81.40, ohio)).isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(39.45, Double.POSITIVE_INFINITY, ohio)).isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(91.0, -81.40, ohio)).isEqualTo(UNKNOWN);
        assertThat(GeoContainment.locate(39.45, -181.0, ohio)).isEqualTo(UNKNOWN);
    }

    // ------------------------------------------------------------- helpers

    private static void assertOutsideButNearFirstVertex(Map<String, Object> geom, double lat, double lng) {
        double[] first = firstVertex(geom);
        assertThat(GeoUtil.haversineKm(lat, lng, first[1], first[0]))
                .as("matchTypeFor would call (%s, %s) a POLYGON match", lat, lng)
                .isLessThanOrEqualTo(FIFTY_MILES_KM);
        assertThat(GeoContainment.locate(lat, lng, geom)).isEqualTo(OUTSIDE);
    }

    private static Containment locatePolygonCoords(Object coordinates) {
        Map<String, Object> g = new HashMap<>();
        g.put("type", "Polygon");
        g.put("coordinates", coordinates);
        return GeoContainment.locate(40.5, -110.5, g);
    }

    /** A valid square ring around (40.5, -110.5) with one vertex replaced. */
    private static List<Object> withVertex(int index, Object vertex) {
        List<Object> r = new ArrayList<>(ring(-111, 40, -110, 41));
        r.set(index, vertex);
        return r;
    }

    private static List<List<Double>> ring(double minLng, double minLat, double maxLng, double maxLat) {
        return List.of(List.of(minLng, minLat), List.of(maxLng, minLat), List.of(maxLng, maxLat),
                List.of(minLng, maxLat), List.of(minLng, minLat));
    }

    @SafeVarargs
    private static Map<String, Object> polygon(List<List<Double>>... rings) {
        return Map.of("type", "Polygon", "coordinates", List.of(rings));
    }

    @SuppressWarnings("unchecked")
    private static double[] firstVertex(Map<String, Object> geom) {
        List<Number> v = ((List<List<List<Number>>>) geom.get("coordinates")).get(0).get(0);
        return new double[] { v.get(0).doubleValue(), v.get(1).doubleValue() };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> geometryOf(JsonNode feature) {
        return JSON.convertValue(feature.get("geometry"), Map.class);
    }

    private static JsonNode read(String resource) throws Exception {
        try (InputStream in = GeoContainmentTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return JSON.readTree(in);
        }
    }
}
