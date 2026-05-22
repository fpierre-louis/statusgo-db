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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Free, key-less address geocoding via OpenStreetMap Nominatim — backs
 * the FE address type-ahead + "use my current location" autofill so we
 * carry no Google Places (autocomplete / geocoding) billing.
 *
 * <ul>
 *   <li>{@link #forwardSearch} — type-ahead: free text → ranked address
 *       suggestions (full {@code display_name} + lat/lng).</li>
 *   <li>{@link #reverse} — coordinates → a single best-match full
 *       address label (for "use my current location").</li>
 * </ul>
 *
 * <p>Per the Nominatim usage policy this sends a real {@code User-Agent}
 * and caches aggressively (6 h on success). Never throws — returns an
 * empty list / null on any upstream failure. The FE additionally
 * debounces type-ahead input so we stay well under the ~1 req/s rate.</p>
 *
 * <p>Distinct from {@link NominatimGeocodeService} (which resolves a
 * coarse {city, region, state} {@code Place} for feed labels) — this one
 * deals in full street-address strings + multiple forward candidates.</p>
 */
@Service
public class GeocodeService {

    private static final Logger log = LoggerFactory.getLogger(GeocodeService.class);

    /** A geocode candidate. {@code label} is Nominatim's full display name. */
    public record Suggestion(String label, Double lat, Double lng) {}

    private static final String SEARCH = "https://nominatim.openstreetmap.org/search";
    private static final String REVERSE = "https://nominatim.openstreetmap.org/reverse";

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 8;

    private static final long TTL_OK_MS = Duration.ofHours(6).toMillis();
    private static final long TTL_FAIL_MS = Duration.ofMinutes(5).toMillis();
    /** ~0.0005° ≈ 55 m buckets — fine enough that a "use my location" fix
     *  resolves to the right building, coarse enough to coalesce repeats. */
    private static final double Q = 0.0005;

    private final ObjectMapper objectMapper;
    private final RestTemplate rest;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Value("${nominatim.user-agent:SitPrep/1.0 (contact@sitprep.app)}")
    private String userAgent;

    public GeocodeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.rest = new RestTemplate(factory);
    }

    /** Forward type-ahead: free text → up to {@code limit} US address candidates. */
    @SuppressWarnings("unchecked")
    public List<Suggestion> forwardSearch(String query, Integer limit) {
        if (query == null || query.isBlank() || query.trim().length() < 3) return List.of();
        int lim = (limit == null || limit < 1) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        String key = "f|" + query.trim().toLowerCase(Locale.US) + "|" + lim;
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) return (List<Suggestion>) cached.payload;

        List<Suggestion> out = new ArrayList<>();
        long ttl = TTL_FAIL_MS;
        try {
            URI uri = URI.create(SEARCH
                    + "?format=jsonv2&addressdetails=1&countrycodes=us"
                    + "&limit=" + lim
                    + "&q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
            JsonNode root = getJson(uri);
            if (root != null && root.isArray()) {
                for (JsonNode n : root) {
                    String label = text(n, "display_name");
                    double la = n.path("lat").asDouble(Double.NaN);
                    double lo = n.path("lon").asDouble(Double.NaN);
                    if (label != null && Double.isFinite(la) && Double.isFinite(lo)) {
                        out.add(new Suggestion(label, la, lo));
                    }
                }
                ttl = TTL_OK_MS;
            }
        } catch (Exception e) {
            log.debug("Nominatim forward search failed for '{}': {}", query, e.getMessage());
        }
        cache.put(key, new CacheEntry(out, System.currentTimeMillis() + ttl));
        return out;
    }

    /** Reverse: coordinates → single best full-address label, or null. */
    public Suggestion reverse(Double lat, Double lng) {
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)) return null;

        String key = String.format(Locale.US, "r|%.4f|%.4f",
                Math.round(lat / Q) * Q, Math.round(lng / Q) * Q);
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) return (Suggestion) cached.payload;

        Suggestion s = null;
        long ttl = TTL_FAIL_MS;
        try {
            for (int zoom : new int[] { 18, 17, 16 }) {
                URI uri = URI.create(REVERSE
                        + "?format=jsonv2&addressdetails=1&zoom=" + zoom
                        + "&lat=" + String.format(Locale.US, "%.6f", lat)
                        + "&lon=" + String.format(Locale.US, "%.6f", lng));
                JsonNode root = getJson(uri);
                if (root != null) {
                    String label = text(root, "display_name");
                    if (label != null) {
                        s = new Suggestion(label, lat, lng);
                        ttl = TTL_OK_MS;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Nominatim reverse failed at {},{}: {}", lat, lng, e.getMessage());
        }
        cache.put(key, new CacheEntry(s, System.currentTimeMillis() + ttl));
        return s;
    }

    // ── helpers ─────────────────────────────────────────────────────
    private JsonNode getJson(URI uri) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.USER_AGENT, userAgent);
        ResponseEntity<String> res = rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null && !res.getBody().isBlank()) {
            return objectMapper.readTree(res.getBody());
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static final class CacheEntry {
        final Object payload;
        final long expiresAtMs;
        CacheEntry(Object payload, long expiresAtMs) {
            this.payload = payload;
            this.expiresAtMs = expiresAtMs;
        }
        boolean isExpired() { return System.currentTimeMillis() > expiresAtMs; }
    }
}
