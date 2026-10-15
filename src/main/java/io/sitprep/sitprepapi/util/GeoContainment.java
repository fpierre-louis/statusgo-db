package io.sitprep.sitprepapi.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Is a point inside an official alert area? Answered three ways —
 * {@link Containment#INSIDE}, {@link Containment#OUTSIDE} or
 * {@link Containment#UNKNOWN} — against a GeoJSON {@code Polygon} or
 * {@code MultiPolygon}.
 *
 * <h2>Why this exists (R4)</h2>
 * {@code AlertIngestService.matchTypeFor} labels a match {@code POLYGON} when
 * the polygon's <b>first vertex</b> is within the feed radius (50 mi) of the
 * point ({@code firstCoord}). That is a proximity test wearing a containment
 * label: a household 49 miles from one corner of an evacuation polygon reads as
 * "this polygon covers you", and the Alerts audit traced that through to an
 * evacuation order up to 50 mi away becoming a household's governing directive
 * (safety defect R4). The owner ruled on 2026-10-13 that the governing alert
 * must <i>contain</i> the household's point. This class is that containment
 * test, built and tested on its own so the wiring can be reviewed separately.
 *
 * <p><b>One caller, by design.</b> {@code ActivationDirectiveResolver} uses it
 * to decide whether a {@code polygon}-matched alert may govern an activation's
 * movement directive (only INSIDE may). {@code matchTypeFor} and the feed are
 * unchanged: what an alert surface SHOWS still uses the proximity match
 * ({@code SitPrep FE/docs/epics/alerts/alerts_and_warnings-completed/map_lane_handoff.md}
 * §B1).</p>
 *
 * <h2>Why three answers and not a boolean</h2>
 * {@link GeoJsonArea#contains} (used by {@code MemberAlertAreaService}) answers
 * yes/no, and anything it cannot parse is null, which its caller reads as "not
 * inside". For a roster badge that is fine. For "does this evacuation order
 * govern your household" it is not: a polygon we failed to read is not evidence
 * that you are outside it. So every way the geometry or the point can be wrong
 * comes back {@code UNKNOWN}, and {@code OUTSIDE} only ever means "we read a
 * valid shape and the point is not in it". The caller decides what unknown
 * means for its surface; this class never decides it for them.
 *
 * <p>{@code UNKNOWN} covers: a null geometry; a non-area type ({@code Point},
 * {@code LineString}, {@code GeometryCollection}, or a whole {@code Feature}
 * passed where its {@code geometry} was meant); missing or non-list
 * coordinates; a polygon with no rings; a ring with fewer than four positions or
 * whose last position does not repeat its first (RFC 7946 §3.1.6); a position
 * that is not at least two finite numbers, or is out of range; a ring with zero
 * area; an unsplit antimeridian crossing (below); unparseable JSON text; and a
 * point that is itself non-finite or out of range.</p>
 *
 * <h2>Coordinate order</h2>
 * GeoJSON positions are {@code [lng, lat]} (RFC 7946 §3.1.1) and that is what
 * this class reads. The methods take the <i>point</i> as {@code (lat, lng)},
 * matching {@code matchTypeFor(a, lat, lng, …)} and the rest of the backend.
 * The two orders differ on purpose; the names carry them, and a test pins it.
 *
 * <h2>Points on the line read INSIDE</h2>
 * There is no separate ON_EDGE value. A point on an edge or a vertex — of the
 * outer ring <i>or</i> of a hole — is {@code INSIDE}. Three reasons:
 * <ul>
 *   <li>NWS draws its polygons on a 0.01° grid (about 1 km; every vertex in the
 *       recorded feed has at most two decimals). A household "exactly on the line" is inside
 *       the forecaster's intended area as often as outside it; the line has no
 *       precision finer than a kilometre to give.</li>
 *   <li>The R4 failure mode we are fixing is "told an order applies when it
 *       does not". The opposite failure — an evacuation order not reaching a
 *       household on its boundary — is the worse one, so the tie goes to
 *       inside.</li>
 *   <li>A fourth value would have to be handled by every caller, and the one
 *       that forgets would most likely fall through to "outside" — which is
 *       exactly the silent reading this rule exists to prevent.</li>
 * </ul>
 * This is OGC "covers" semantics (closed polygon), not "contains" (open). The
 * tolerance is {@link #BOUNDARY_TOLERANCE_DEG}, about 0.1 mm: it absorbs
 * floating-point noise from a computed midpoint and is not a buffer. Widening it
 * would quietly grow every alert area.
 *
 * <h2>Holes and MultiPolygons</h2>
 * The first ring of a polygon is its boundary and later rings are holes; a
 * point strictly inside a hole is outside the polygon. A {@code MultiPolygon}
 * is the union of its members, each with its own holes, so the point is inside
 * when any member covers it. If one member is malformed and another valid member
 * covers the point the answer is still {@code INSIDE} — the valid member is
 * enough to know. If no valid member covers it and any member was malformed the
 * answer is {@code UNKNOWN}, because the part we could not read might have.
 * Ring orientation (RFC 7946's right-hand rule) is not required: NWS does not
 * guarantee it and even-odd testing does not need it. Self-intersecting rings
 * are not rejected (checking is O(n²) and NWS polygons are simple); even-odd
 * gives them a deterministic answer.
 *
 * <h2>The antimeridian</h2>
 * Not unwrapped, but never answered wrongly. Containment is planar ray casting
 * on raw degrees, which is right at warning scale (tens of km) anywhere except
 * across ±180°. NWS polygons in practice do not cross it — the 56 in the
 * recorded 2026-08-22 feed and the 2 live on 2026-10-13 all sit between
 * -111.56° and -74.96° longitude, and no edge in them spans more than 1.36° —
 * but "never" is too strong: the western Aleutians lie past 180° and
 * an Alaska marine polygon could straddle the line. RFC 7946 §3.1.9 says such a
 * geometry SHOULD be split into a MultiPolygon at the antimeridian; a split one
 * works here unchanged, since each half sits on one side. An unsplit ring shows
 * up as an edge whose longitude jumps more than 180°, and that ring is treated
 * as malformed ({@code UNKNOWN}) rather than as a polygon wrapped the wrong way
 * round the planet.
 *
 * <p>Pure and stateless: no Spring, no I/O beyond parsing a string the caller
 * passed in.</p>
 */
public final class GeoContainment {

    /** The answer. {@code OUTSIDE} only ever means "a valid shape, and not in it". */
    public enum Containment {
        /** Inside the area, or on its boundary (see the class Javadoc). */
        INSIDE,
        /** A valid area was read and the point is not in it. */
        OUTSIDE,
        /** The geometry or the point could not be read. Never evidence of outside. */
        UNKNOWN
    }

    /**
     * How close (in degrees, planar) a point must be to an edge to count as on
     * it. About 0.1 mm at the equator — floating-point slack, not a buffer.
     */
    static final double BOUNDARY_TOLERANCE_DEG = 1e-9;

    private static final ObjectMapper JSON = new ObjectMapper();

    private GeoContainment() {}

    /**
     * Locate a point against a geometry in the shape the alert pipeline already
     * holds: {@code NormalizedAlert.geometry()} is an {@code Object} that is a
     * Jackson-converted {@code Map} ({@code AlertIngestService.normalizeNws}), or
     * null for the zone-only majority of NWS alerts. A {@link JsonNode} or raw
     * GeoJSON text is accepted too and routed to the same parser.
     *
     * @param lat      the point's latitude
     * @param lng      the point's longitude
     * @param geometry a GeoJSON geometry object ({@code {type, coordinates}}),
     *                 positions in {@code [lng, lat]} order
     */
    public static Containment locate(double lat, double lng, Object geometry) {
        if (geometry instanceof JsonNode node) return locate(lat, lng, node);
        if (geometry instanceof String text) return locateJson(lat, lng, text);
        if (!validPoint(lat, lng)) return Containment.UNKNOWN;
        if (!(geometry instanceof Map<?, ?> map)) return Containment.UNKNOWN;
        return locateMap(lat, lng, map);
    }

    /** As {@link #locate(double, double, Object)}, for a geometry still in Jackson tree form. */
    public static Containment locate(double lat, double lng, JsonNode geometry) {
        if (geometry == null || geometry.isNull() || geometry.isMissingNode() || !geometry.isObject()) {
            return Containment.UNKNOWN;
        }
        // Converting to the same Map/List shape the ingest path produces keeps
        // ONE parser. Jackson keeps a quoted "-81.2" as a String, so a
        // non-numeric coordinate stays non-numeric and comes back UNKNOWN.
        return locate(lat, lng, (Object) JSON.convertValue(geometry, Map.class));
    }

    /** As {@link #locate(double, double, Object)}, for raw GeoJSON geometry text. */
    public static Containment locateJson(double lat, double lng, String geoJson) {
        if (geoJson == null || geoJson.isBlank()) return Containment.UNKNOWN;
        JsonNode node;
        try {
            node = JSON.readTree(geoJson);
        } catch (Exception unreadable) {
            return Containment.UNKNOWN;
        }
        return locate(lat, lng, node);
    }

    // ---------------------------------------------------------------- parsing

    private static Containment locateMap(double lat, double lng, Map<?, ?> geometry) {
        Object type = geometry.get("type");
        Object coords = geometry.get("coordinates");
        if (!(type instanceof String t) || !(coords instanceof List<?> list)) return Containment.UNKNOWN;

        List<?> polygons;
        switch (t) {
            case "Polygon" -> polygons = List.of(list);
            case "MultiPolygon" -> polygons = list;
            // Point (a quake epicentre has no inside), LineString, GeometryCollection,
            // a Feature passed whole: not an area we can answer for.
            default -> { return Containment.UNKNOWN; }
        }
        if (polygons.isEmpty()) return Containment.UNKNOWN;

        boolean anyMalformed = false;
        for (Object raw : polygons) {
            double[][][] rings = parsePolygon(raw);
            if (rings == null) {
                anyMalformed = true;
                continue;
            }
            if (coversPolygon(rings, lat, lng)) return Containment.INSIDE;
        }
        return anyMalformed ? Containment.UNKNOWN : Containment.OUTSIDE;
    }

    /** Rings as {@code [ring][vertex]{lng, lat}}, or null if any ring is malformed. */
    private static double[][][] parsePolygon(Object raw) {
        if (!(raw instanceof List<?> rings) || rings.isEmpty()) return null;
        List<double[][]> out = new ArrayList<>(rings.size());
        for (Object r : rings) {
            double[][] ring = parseRing(r);
            // A malformed hole makes the whole polygon unreadable: dropping it
            // would turn "inside the hole" into "inside the polygon".
            if (ring == null) return null;
            out.add(ring);
        }
        return out.toArray(new double[0][][]);
    }

    private static double[][] parseRing(Object raw) {
        if (!(raw instanceof List<?> positions) || positions.size() < 4) return null;
        double[][] ring = new double[positions.size()][];
        for (int i = 0; i < positions.size(); i++) {
            double[] v = parsePosition(positions.get(i));
            if (v == null) return null;
            ring[i] = v;
        }
        double[] first = ring[0], last = ring[ring.length - 1];
        if (first[0] != last[0] || first[1] != last[1]) return null;   // unclosed
        for (int i = 1; i < ring.length; i++) {
            // Unsplit antimeridian crossing — see the class Javadoc.
            if (Math.abs(ring[i][0] - ring[i - 1][0]) > 180.0) return null;
        }
        if (signedArea(ring) == 0.0) return null;                      // degenerate
        return ring;
    }

    /** {@code [lng, lat, (alt…)]} -> {@code {lng, lat}}; null unless two finite, in-range numbers. */
    private static double[] parsePosition(Object raw) {
        if (!(raw instanceof List<?> p) || p.size() < 2) return null;
        if (!(p.get(0) instanceof Number x) || !(p.get(1) instanceof Number y)) return null;
        double lng = x.doubleValue(), lat = y.doubleValue();
        if (!validPoint(lat, lng)) return null;
        return new double[] { lng, lat };
    }

    private static boolean validPoint(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90.0 && lat <= 90.0
                && lng >= -180.0 && lng <= 180.0;
    }

    private static double signedArea(double[][] ring) {
        double sum = 0;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            sum += (ring[j][0] * ring[i][1]) - (ring[i][0] * ring[j][1]);
        }
        return sum / 2.0;
    }

    // ------------------------------------------------------------ containment

    /** Closed-polygon test: boundary counts, a hole's interior does not. */
    private static boolean coversPolygon(double[][][] rings, double lat, double lng) {
        for (double[][] ring : rings) {
            if (onRing(ring, lat, lng)) return true;
        }
        if (!evenOdd(rings[0], lat, lng)) return false;
        for (int h = 1; h < rings.length; h++) {
            if (evenOdd(rings[h], lat, lng)) return false;
        }
        return true;
    }

    /**
     * Even-odd ray cast towards +lng. Boundary points never reach here
     * ({@link #coversPolygon} answers them first), so the half-open vertex rule
     * only has to be consistent, not "right".
     */
    private static boolean evenOdd(double[][] ring, double lat, double lng) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0], yi = ring[i][1];
            double xj = ring[j][0], yj = ring[j][1];
            if ((yi > lat) != (yj > lat)
                    && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean onRing(double[][] ring, double lat, double lng) {
        for (int i = 1; i < ring.length; i++) {
            if (onSegment(ring[i - 1], ring[i], lat, lng)) return true;
        }
        return false;
    }

    private static boolean onSegment(double[] a, double[] b, double lat, double lng) {
        double tol = BOUNDARY_TOLERANCE_DEG;
        if (lng < Math.min(a[0], b[0]) - tol || lng > Math.max(a[0], b[0]) + tol
                || lat < Math.min(a[1], b[1]) - tol || lat > Math.max(a[1], b[1]) + tol) {
            return false;
        }
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double len = Math.hypot(dx, dy);
        if (len == 0.0) return true;   // repeated vertex; the bbox check above already matched it
        // Perpendicular distance from the point to the line through a and b.
        double cross = dx * (lat - a[1]) - dy * (lng - a[0]);
        return Math.abs(cross) <= tol * len;
    }
}
