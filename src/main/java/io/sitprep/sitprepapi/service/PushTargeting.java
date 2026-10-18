package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.GeoContainment;
import io.sitprep.sitprepapi.util.GeoUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * R19 (owner, 2026-10-15): who a severe NWS warning pushes to.
 *
 * <p>Replaces "anyone within 80 km of the alert's representative point". A
 * warning drawn over one county used to wake people two counties away, and a
 * long thin warning could miss its own far end. Modelled on Wireless Emergency
 * Alerts, which must reach the whole drawn area with ≤0.1 mile of overshoot:
 * a buffer stands in for that tolerance because our locations can be up to 14
 * days old ({@code LocationFreshness}).</p>
 *
 * <ul>
 *   <li><b>An alert with an outline:</b> the person's last-known point is
 *       inside it, or within {@link #BUFFER_KM} of its edge.</li>
 *   <li><b>A zone-only alert</b> (about 82% of NWS): the point's NWS zones
 *       (forecast zone, county, fire zone) intersect the alert's target codes
 *       (UGC plus SAME counties). Zones come from the cache only — never a
 *       network call inside dispatch.</li>
 *   <li><b>Fail OPEN, never silent.</b> When a person's zones are not cached
 *       yet (just after a restart, a new location), or the outline can't be
 *       read, that person falls back to the old rule: within
 *       {@link #FALLBACK_RADIUS_KM} of the alert's point. Like WEA, which
 *       delivers when the phone cannot determine its location.</li>
 *   <li><b>At home:</b> household members whose household HOME is inside the
 *       area (same test, no fallback — the location rule above already covers
 *       them where they are) get the push too, labelled "At your home".</li>
 * </ul>
 * <p>Earthquakes never come here: shaking has no outline, and they stay on
 * distance from the epicentre (the FCC waived precise WEA targeting for USGS
 * alerts for the same reason). Pure: no I/O. Nothing here is AI.</p>
 */
final class PushTargeting {

    private PushTargeting() {}

    /** Slack around an outline for a stale or imprecise fix. */
    static final double BUFFER_KM = 5.0;

    /** The old rule, kept as the per-person fallback. */
    static final double FALLBACK_RADIUS_KM = 80.0;

    static final String BASIS_OUTLINE = "outline+5km";
    static final String BASIS_ZONES = "zones";
    static final String BASIS_PROXIMITY = "proximity";

    /**
     * The result. {@code here} and {@code atHome} never share a person;
     * counts only in {@code audit}.
     */
    record Plan(List<UserInfo> here, List<UserInfo> atHome, Audit audit) {}

    record Audit(String basis, int reachedHere, int reachedHome, int fallbackProximity,
                 int proximityWould, int addedVsProximity, int droppedVsProximity) {}

    /**
     * @param coord       the alert's representative point, {@code [lng, lat]}
     * @param candidates  pushable people with a fresh-enough location
     * @param households  households whose home is near the area (members fetched)
     * @param homeUsers   loads pushable people by lower-cased email
     * @param cachedZones {@code (lat, lng) -> zones}, from the cache only
     */
    static Plan plan(NormalizedAlert a, double[] coord, List<UserInfo> candidates, List<Group> households,
                     java.util.function.Function<Set<String>, List<UserInfo>> homeUsers,
                     BiFunction<Double, Double, Optional<Set<String>>> cachedZones) {
        Object outline = a.geometry();
        Set<String> targets = upper(AlertIngestService.targetCodes(a));
        String basis = outline != null ? BASIS_OUTLINE : !targets.isEmpty() ? BASIS_ZONES : BASIS_PROXIMITY;

        List<UserInfo> here = new ArrayList<>();
        Set<String> hereEmails = new HashSet<>();
        Set<String> proximity = new HashSet<>();
        int fallback = 0;
        Set<String> seen = new HashSet<>();
        for (UserInfo u : candidates == null ? List.<UserInfo>of() : candidates) {
            String email = emailOf(u);
            if (email == null || u.getLastKnownLat() == null || u.getLastKnownLng() == null) continue;
            if (!seen.add(email)) continue;
            double lat = u.getLastKnownLat(), lng = u.getLastKnownLng();
            boolean near = coord != null && GeoUtil.haversineKm(coord[1], coord[0], lat, lng) <= FALLBACK_RADIUS_KM;
            if (near) proximity.add(email);
            Coverage c = covers(outline, targets, lat, lng, cachedZones);
            boolean take = switch (c) {
                case YES -> true;
                case NO -> false;
                case UNKNOWN -> {
                    if (near) fallback++;
                    yield near;
                }
            };
            if (take) {
                here.add(u);
                hereEmails.add(email);
            }
        }

        // At home: members of a household whose home is in the area, not already reached.
        Set<String> homeEmails = new LinkedHashSet<>();
        if (!BASIS_PROXIMITY.equals(basis)) {
            for (Group g : households == null ? List.<Group>of() : households) {
                Double hl = g.getLatitude(), hg = g.getLongitude();
                if (hl == null || hg == null) continue;
                if (covers(outline, targets, hl, hg, cachedZones) != Coverage.YES) continue;
                addEmail(homeEmails, g.getOwnerEmail());
                if (g.getAdminEmails() != null) g.getAdminEmails().forEach(e -> addEmail(homeEmails, e));
                if (g.getMemberEmails() != null) g.getMemberEmails().forEach(e -> addEmail(homeEmails, e));
            }
            homeEmails.removeAll(hereEmails);
        }
        List<UserInfo> atHome = new ArrayList<>();
        if (!homeEmails.isEmpty() && homeUsers != null) {
            Set<String> added = new HashSet<>();
            for (UserInfo u : homeUsers.apply(homeEmails)) {
                String email = emailOf(u);
                if (email == null || hereEmails.contains(email) || !added.add(email)) continue;
                if (u.getFcmtoken() == null || u.getFcmtoken().isBlank()) continue;
                atHome.add(u);
            }
        }

        Set<String> reached = new HashSet<>(hereEmails);
        atHome.forEach(u -> reached.add(emailOf(u)));
        int both = 0;
        for (String e : reached) if (proximity.contains(e)) both++;
        Audit audit = new Audit(basis, here.size(), atHome.size(), fallback, proximity.size(),
                reached.size() - both, proximity.size() - both);
        return new Plan(here, atHome, audit);
    }

    enum Coverage { YES, NO, UNKNOWN }

    /** Is (lat, lng) in the alert's area? UNKNOWN when it can't be told. */
    static Coverage covers(Object outline, Set<String> targets, double lat, double lng,
                           BiFunction<Double, Double, Optional<Set<String>>> cachedZones) {
        if (outline != null) {
            GeoContainment.Containment c = GeoContainment.locate(lat, lng, outline);
            if (c == GeoContainment.Containment.INSIDE || withinKmOfOutline(lat, lng, outline, BUFFER_KM)) {
                return Coverage.YES;
            }
            return c == GeoContainment.Containment.UNKNOWN ? Coverage.UNKNOWN : Coverage.NO;
        }
        if (targets.isEmpty()) return Coverage.UNKNOWN;
        Optional<Set<String>> zones = cachedZones == null ? Optional.empty() : cachedZones.apply(lat, lng);
        if (zones.isEmpty() || zones.get().isEmpty()) return Coverage.UNKNOWN;
        for (String z : zones.get()) {
            if (z != null && targets.contains(z.trim().toUpperCase(Locale.ROOT))) return Coverage.YES;
        }
        return Coverage.NO;
    }

    /** The box to look for household homes in: the outline's box plus the buffer, else the fallback circle's. */
    static double[] homeSearchBox(NormalizedAlert a, double[] coord) {
        double padKm = BUFFER_KM;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        for (double[][] ring : rings(a.geometry())) {
            for (double[] p : ring) {
                minLng = Math.min(minLng, p[0]); maxLng = Math.max(maxLng, p[0]);
                minLat = Math.min(minLat, p[1]); maxLat = Math.max(maxLat, p[1]);
            }
        }
        if (minLat == Double.MAX_VALUE) {
            if (coord == null) return null;
            padKm = FALLBACK_RADIUS_KM;
            minLat = maxLat = coord[1];
            minLng = maxLng = coord[0];
        }
        double dLat = padKm / 110.574;
        double dLng = padKm / (111.32 * Math.max(0.2, Math.cos(Math.toRadians((minLat + maxLat) / 2))));
        return new double[] { minLat - dLat, maxLat + dLat, minLng - dLng, maxLng + dLng };
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

    private static Set<String> upper(List<String> codes) {
        Set<String> out = new HashSet<>();
        if (codes != null) for (String c : codes) if (c != null && !c.isBlank()) out.add(c.trim().toUpperCase(Locale.ROOT));
        return out;
    }

    private static void addEmail(Set<String> into, String email) {
        if (email != null && !email.isBlank()) into.add(email.trim().toLowerCase(Locale.ROOT));
    }

    private static String emailOf(UserInfo u) {
        return u == null || u.getUserEmail() == null ? null : u.getUserEmail().trim().toLowerCase(Locale.ROOT);
    }
}
