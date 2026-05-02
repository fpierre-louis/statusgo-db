package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reverse-geocoding via OpenStreetMap Nominatim. Free, no API key needed —
 * but the operator's TOS requires a real {@code User-Agent} with contact info,
 * a low call rate, and that we cache aggressively.
 *
 * <p>Used by SitPrep to translate (lat, lng) into a {@code {city, region,
 * state, country}} bundle for community discover, post-location labels,
 * and the user's home-location summary. The full {@link Place} record is
 * persisted alongside the user / saved location so subsequent reads don't
 * re-resolve.</p>
 *
 * <p>Quantizes coordinates to ~1 km buckets before caching so two close
 * locations hit the same key. In-memory cache: 6 h on success, 5 min on
 * failure. Never throws — returns {@code null} if Nominatim is unreachable
 * or the response is unusable.</p>
 */
@Service
public class NominatimGeocodeService {

    private static final Logger log = LoggerFactory.getLogger(NominatimGeocodeService.class);

    /**
     * Reverse-geocode result. All fields nullable when Nominatim doesn't supply them.
     *
     * <p>{@code neighborhood} is the most-local-resolvable label (suburb /
     * neighbourhood / quarter from Nominatim's address breakdown) — used by
     * post feed cards for the Nextdoor-style "{neighborhood} · {time}"
     * subtitle. Falls back to {@code city} on the consumer side when null.</p>
     */
    public record Place(String neighborhood, String city, String region,
                        String state, String country, String zipBucket) {

        /**
         * Best-available short label for "where this is", in the order:
         * neighborhood → city → region → state. Null when nothing resolves.
         * Used by services that want a single human-readable place string
         * (post {@code placeLabel}) without re-implementing the fallback.
         */
        public String shortLabel() {
            if (neighborhood != null && !neighborhood.isBlank()) return neighborhood;
            if (city != null && !city.isBlank()) return city;
            if (region != null && !region.isBlank()) return region;
            return state;
        }
    }

    private static final String BASE = "https://nominatim.openstreetmap.org/reverse";
    private static final long TTL_OK_MS = Duration.ofHours(6).toMillis();
    private static final long TTL_FAIL_MS = Duration.ofMinutes(5).toMillis();

    /** ~0.01° ≈ 1.1 km → coarse enough to coalesce neighborhood-level requests. */
    private static final double Q = 0.01;

    private final ObjectMapper objectMapper;
    private final RestTemplate rest = new RestTemplate();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Value("${nominatim.user-agent:SitPrep/1.0 (contact@sitprep.app)}")
    private String userAgent;

    public NominatimGeocodeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Resolve (lat, lng) → {@link Place}. Returns null on error or when no
     * usable address is returned. Safe to call from request paths — never
     * throws.
     */
    public Place reverse(Double lat, Double lng) {
        if (lat == null || lng == null) return null;
        if (!Double.isFinite(lat) || !Double.isFinite(lng)) return null;

        String key = bucketKey(lat, lng);
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) return cached.place;

        Place place = null;
        long ttl = TTL_FAIL_MS;

        try {
            // zoom=18 (building level) returns the full address breakdown
            // including suburb / neighbourhood / quarter so the Place can
            // carry a useful "neighborhood" field for feed-card subtitles.
            // The 6h cache + ~1km bucket key keeps the request volume low
            // even at higher zoom.
            URI uri = URI.create(BASE
                    + "?format=jsonv2"
                    + "&lat=" + String.format(Locale.US, "%.6f", lat)
                    + "&lon=" + String.format(Locale.US, "%.6f", lng)
                    + "&zoom=18"
                    + "&addressdetails=1"
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            headers.set(HttpHeaders.USER_AGENT, userAgent);

            HttpEntity<Void> req = new HttpEntity<>(headers);
            ResponseEntity<String> res = rest.exchange(uri, HttpMethod.GET, req, String.class);

            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null && !res.getBody().isBlank()) {
                JsonNode root = objectMapper.readTree(res.getBody());
                JsonNode addr = root.path("address");

                // Neighborhood prefers the most-local OSM key Nominatim
                // tends to fill in for residential addresses. Falling back
                // through suburb/quarter/residential covers most cities;
                // null is fine — the consumer falls back to city.
                String neighborhood = pickFirst(addr,
                        "neighbourhood", "suburb", "quarter", "city_district", "residential");
                String city = pickFirst(addr, "city", "town", "village", "hamlet", "municipality", "county");
                String region = pickFirst(addr, "state_district", "region", "province");
                String state = pickFirst(addr, "state");
                String country = pickFirst(addr, "country");
                String postcode = trimOrNull(text(addr, "postcode"));
                String zipBucket = postcode == null ? null
                        : postcode.length() >= 3 ? postcode.substring(0, 3) : postcode;

                if (neighborhood != null || city != null || region != null || state != null) {
                    place = new Place(
                            trimOrNull(neighborhood),
                            trimOrNull(city),
                            trimOrNull(region),
                            trimOrNull(state),
                            trimOrNull(country),
                            zipBucket
                    );
                    ttl = TTL_OK_MS;
                }
            }
        } catch (Exception e) {
            log.debug("Nominatim reverse-geocode failed at lat={} lng={}: {}", lat, lng, e.getMessage());
        }

        cache.put(key, new CacheEntry(place, System.currentTimeMillis() + ttl));
        return place;
    }

    private static String pickFirst(JsonNode addr, String... fields) {
        for (String f : fields) {
            String v = text(addr, f);
            if (!isBlank(v)) return v;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.asText(null);
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private static String trimOrNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private static String bucketKey(double lat, double lng) {
        double qLat = Math.round(lat / Q) * Q;
        double qLng = Math.round(lng / Q) * Q;
        return String.format(Locale.US, "%.2f|%.2f", qLat, qLng);
    }

    private static final class CacheEntry {
        final Place place;
        final long expiresAtMs;

        CacheEntry(Place place, long expiresAtMs) {
            this.place = place;
            this.expiresAtMs = expiresAtMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }
}
