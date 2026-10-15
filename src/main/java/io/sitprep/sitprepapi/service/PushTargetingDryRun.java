package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.GeoContainment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * R19 DRY RUN (owner, 2026-10-15): who a CONTAINMENT rule would push for a
 * severe NWS alert, compared with who today's proximity rule
 * ({@code SEVERE_PUSH_RADIUS_KM} of the alert's representative point) pushes.
 * It changes NOTHING about delivery: {@link AlertDispatchService} logs the
 * comparison as counts only, so the owner can see a few days of real alerts
 * before the rule switches.
 *
 * <p>The containment rule being measured:</p>
 * <ul>
 *   <li>an alert with an outline: the person's last-known point is inside it,
 *       or within {@link #BUFFER_KM} of its edge (stale or imprecise fixes);</li>
 *   <li>a zone-only alert (most NWS alerts): the point's NWS zones intersect
 *       the alert's target codes (UGC plus SAME counties). Zones come from
 *       the CACHE only, never a network call inside dispatch. A point with
 *       no cached zones counts as {@code unknown}, never as outside.</li>
 * </ul>
 * <p>Earthquakes stay on proximity (shaking has no outline) and are never
 * passed here. Nothing here is AI.</p>
 */
final class PushTargetingDryRun {

    private PushTargetingDryRun() {}

    /** Slack around an outline for a stale or imprecise fix. */
    static final double BUFFER_KM = 5.0;

    /** Above this many candidates the comparison is skipped (it is O(people × edges)). */
    static final int MAX_CANDIDATES = 20_000;

    record Comparison(int proximity, int containment, int both, int proximityOnly,
                      int containmentOnly, int unknown, String basis) {
        String logLine(String alertRef) {
            return String.format(Locale.ROOT,
                    "R19 dry-run %s (%s): proximity=%d containment=%d both=%d proximityOnly=%d containmentOnly=%d unknown=%d",
                    alertRef, basis, proximity, containment, both, proximityOnly, containmentOnly, unknown);
        }
    }

    /**
     * @param proximityEmails who today's rule reached (lower-cased)
     * @param cachedZones     {@code (lat, lng) -> zones} from the cache only
     */
    static Comparison compare(NormalizedAlert a, List<UserInfo> candidates, Set<String> proximityEmails,
                              BiFunction<Double, Double, Optional<Set<String>>> cachedZones) {
        boolean hasOutline = a.geometry() != null;
        Set<String> targets = new HashSet<>(AlertIngestService.targetCodes(a));
        Set<String> contained = new HashSet<>();
        int unknown = 0;
        Set<String> seen = new HashSet<>();
        for (UserInfo u : candidates) {
            if (u == null || u.getUserEmail() == null || u.getLastKnownLat() == null || u.getLastKnownLng() == null) continue;
            String email = u.getUserEmail().trim().toLowerCase(Locale.ROOT);
            if (!seen.add(email)) continue;
            double lat = u.getLastKnownLat(), lng = u.getLastKnownLng();
            if (hasOutline) {
                GeoContainment.Containment c = GeoContainment.locate(lat, lng, a.geometry());
                if (c == GeoContainment.Containment.INSIDE || withinKmOfOutline(lat, lng, a.geometry(), BUFFER_KM)) {
                    contained.add(email);
                } else if (c == GeoContainment.Containment.UNKNOWN) {
                    unknown++;
                }
            } else if (!targets.isEmpty()) {
                Optional<Set<String>> zones = cachedZones.apply(lat, lng);
                if (zones.isEmpty()) { unknown++; continue; }
                for (String z : zones.get()) {
                    if (z != null && targets.contains(z.trim().toUpperCase(Locale.ROOT))) { contained.add(email); break; }
                }
            }
        }
        int both = 0;
        for (String e : contained) if (proximityEmails.contains(e)) both++;
        return new Comparison(proximityEmails.size(), contained.size(), both,
                proximityEmails.size() - both, contained.size() - both, unknown,
                hasOutline ? "outline+" + (int) BUFFER_KM + "km" : "zones");
    }

    /** Is the point within {@code km} of any edge of a (Multi)Polygon? Planar, fine at these distances. */
    static boolean withinKmOfOutline(double lat, double lng, Object geometry, double km) {
        for (double[][] ring : rings(geometry)) {
            for (int i = 0; i + 1 < ring.length; i++) {
                if (segmentDistanceKm(lat, lng, ring[i], ring[i + 1]) <= km) return true;
            }
        }
        return false;
    }

    private static double segmentDistanceKm(double lat, double lng, double[] p1, double[] p2) {
        // Equirectangular projection around the point: x = lng·cos(lat), y = lat (degrees → km).
        double kx = 111.32 * Math.cos(Math.toRadians(lat)), ky = 110.574;
        double ax = (p1[0] - lng) * kx, ay = (p1[1] - lat) * ky;
        double bx = (p2[0] - lng) * kx, by = (p2[1] - lat) * ky;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        double cx = ax + t * dx, cy = ay + t * dy;
        return Math.sqrt(cx * cx + cy * cy);
    }

    /** Every ring of a GeoJSON Polygon / MultiPolygon map, as [lng, lat] positions. */
    @SuppressWarnings("unchecked")
    private static List<double[][]> rings(Object geometry) {
        List<double[][]> out = new ArrayList<>();
        if (!(geometry instanceof Map<?, ?> g)) return out;
        Object type = g.get("type"), coords = g.get("coordinates");
        if (!(coords instanceof List<?> list)) return out;
        if ("Polygon".equals(type)) {
            for (Object ring : list) addRing(out, ring);
        } else if ("MultiPolygon".equals(type)) {
            for (Object poly : list) if (poly instanceof List<?> rs) for (Object ring : rs) addRing(out, ring);
        }
        return out;
    }

    private static void addRing(List<double[][]> out, Object ring) {
        if (!(ring instanceof List<?> pts)) return;
        List<double[]> ok = new ArrayList<>();
        for (Object p : pts) {
            if (p instanceof List<?> xy && xy.size() >= 2 && xy.get(0) instanceof Number x && xy.get(1) instanceof Number y) {
                ok.add(new double[] { x.doubleValue(), y.doubleValue() });
            }
        }
        if (ok.size() >= 2) out.add(ok.toArray(new double[0][]));
    }
}
