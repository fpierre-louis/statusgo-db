package io.sitprep.sitprepapi.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A GeoJSON {@code Polygon} / {@code MultiPolygon} parsed once into rings, for
 * repeated point-in-area tests (one roster read tests every located member
 * against every polygon alert).
 *
 * <p>Coordinates are GeoJSON order, {@code [lng, lat]}. The first ring of each
 * polygon is its boundary; later rings are holes. Containment is even-odd ray
 * casting on the plane — accurate at warning-polygon scale (tens of km), which
 * is what NWS storm-based polygons are. No PostGIS here by design.</p>
 */
public final class GeoJsonArea {

    /** polygons → rings → vertices as {lng, lat}. */
    private final List<List<double[][]>> polygons;
    private final double minLat, maxLat, minLng, maxLng;

    private GeoJsonArea(List<List<double[][]>> polygons) {
        this.polygons = polygons;
        double a = Double.MAX_VALUE, b = -Double.MAX_VALUE, c = Double.MAX_VALUE, d = -Double.MAX_VALUE;
        for (List<double[][]> poly : polygons) {
            for (double[] v : poly.get(0)) {
                c = Math.min(c, v[0]); d = Math.max(d, v[0]);
                a = Math.min(a, v[1]); b = Math.max(b, v[1]);
            }
        }
        this.minLat = a; this.maxLat = b; this.minLng = c; this.maxLng = d;
    }

    /**
     * Parse a raw GeoJSON geometry map. Null for anything that is not a usable
     * area — a {@code Point} (an earthquake epicentre has no inside), an
     * unknown type, or malformed coordinates.
     */
    @SuppressWarnings("unchecked")
    public static GeoJsonArea parse(Object geometry) {
        if (!(geometry instanceof Map<?, ?> m)) return null;
        Object type = m.get("type");
        Object coords = m.get("coordinates");
        if (!(type instanceof String t) || !(coords instanceof List<?>)) return null;
        try {
            List<List<double[][]>> polys = new ArrayList<>();
            switch (t) {
                case "Polygon" -> addPolygon(polys, (List<Object>) coords);
                case "MultiPolygon" -> {
                    for (Object p : (List<Object>) coords) addPolygon(polys, (List<Object>) p);
                }
                default -> { return null; }
            }
            return polys.isEmpty() ? null : new GeoJsonArea(polys);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void addPolygon(List<List<double[][]>> out, List<Object> rings) {
        List<double[][]> parsed = new ArrayList<>();
        for (Object r : rings) {
            List<Object> ring = (List<Object>) r;
            double[][] vs = new double[ring.size()][];
            for (int i = 0; i < ring.size(); i++) {
                List<Number> v = (List<Number>) ring.get(i);
                vs[i] = new double[] { v.get(0).doubleValue(), v.get(1).doubleValue() };
            }
            if (vs.length >= 3) parsed.add(vs);
        }
        if (!parsed.isEmpty()) out.add(parsed);
    }

    /** True when the point lies inside a polygon's boundary and outside its holes. */
    public boolean contains(double lat, double lng) {
        if (lat < minLat || lat > maxLat || lng < minLng || lng > maxLng) return false;
        for (List<double[][]> poly : polygons) {
            if (!ringContains(poly.get(0), lat, lng)) continue;
            boolean inHole = false;
            for (int h = 1; h < poly.size() && !inHole; h++) {
                inHole = ringContains(poly.get(h), lat, lng);
            }
            if (!inHole) return true;
        }
        return false;
    }

    private static boolean ringContains(double[][] ring, double lat, double lng) {
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
}
