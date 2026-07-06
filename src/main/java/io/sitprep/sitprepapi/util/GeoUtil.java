package io.sitprep.sitprepapi.util;

public final class GeoUtil {

    public static final double MI_TO_KM = 1.609344;
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private static final double DEG_LAT_KM = 111.045;

    private GeoUtil() {
    }

    public static double milesToKm(double miles) {
        return miles * MI_TO_KM;
    }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLng / 2), 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    public static GeoBox around(double lat, double lng, double radiusKm) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || !Double.isFinite(radiusKm) || radiusKm < 0) {
            throw new IllegalArgumentException("Valid lat, lng, and radius are required");
        }
        double latDelta = radiusKm / DEG_LAT_KM;
        double latMin = clamp(lat - latDelta, -90.0, 90.0);
        double latMax = clamp(lat + latDelta, -90.0, 90.0);

        double cos = Math.cos(Math.toRadians(lat));
        if (Math.abs(cos) < 1.0e-6) {
            return new GeoBox(latMin, latMax, -180.0, 180.0);
        }

        double lngDelta = radiusKm / (DEG_LAT_KM * Math.abs(cos));
        if (lngDelta >= 180.0) {
            return new GeoBox(latMin, latMax, -180.0, 180.0);
        }

        double lngMin = normalizeLng(lng - lngDelta);
        double lngMax = normalizeLng(lng + lngDelta);
        if (lngMin > lngMax) {
            // v1 is US-centered; use a safe over-select instead of missing
            // antimeridian candidates.
            return new GeoBox(latMin, latMax, -180.0, 180.0);
        }
        return new GeoBox(latMin, latMax, lngMin, lngMax);
    }

    public static boolean validLatLng(Double lat, Double lng) {
        return lat != null && lng != null
                && Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90.0 && lat <= 90.0
                && lng >= -180.0 && lng <= 180.0;
    }

    /**
     * Write-boundary guard for user-supplied coordinate pairs. A fully-null
     * pair passes (optional-location writes; callers with mandatory coords
     * null-check first). A half-null pair, non-finite value, or out-of-range
     * value throws {@link IllegalArgumentException}, which
     * {@code GlobalExceptionHandler} maps to 400 BAD_REQUEST.
     */
    public static void requireValidLatLng(Double lat, Double lng) {
        if (lat == null && lng == null) return;
        if (!validLatLng(lat, lng)) {
            throw new IllegalArgumentException(
                    "latitude must be within [-90, 90] and longitude within [-180, 180]");
        }
    }

    private static double normalizeLng(double lng) {
        double value = lng;
        while (value < -180.0) value += 360.0;
        while (value > 180.0) value -= 360.0;
        return value;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record GeoBox(double latMin, double latMax, double lngMin, double lngMax) {}
}
