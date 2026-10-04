package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.util.NominatimThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * THE outbound geocoding client (open-items 3.4, owner 2026-10-03). Every
 * address lookup the server makes goes through here — there were three
 * hand-rolled Nominatim clients ({@code GeocodeService},
 * {@code NominatimGeocodeService}, {@code ShelterSearchService}), one of them
 * with no HTTP timeouts at all, each with its own unbounded cache.
 *
 * <ul>
 *   <li><b>Photon</b> (OpenStreetMap, built for search-as-you-type) serves
 *       the address TYPE-AHEAD. Nominatim's usage policy forbids building
 *       autocomplete on its public server, and ours was doing exactly that.</li>
 *   <li><b>Nominatim</b> serves one-shot forward lookups and every reverse
 *       lookup, under the shared {@link NominatimThrottle} (≤ 1 req/s).</li>
 * </ul>
 *
 * <p>One identifying User-Agent, one set of timeouts, and ONE bounded cache
 * (least-recently-used past {@link #CACHE_MAX} entries) for all callers.
 * Never throws: a failure is a miss (null), cached briefly so a dead upstream
 * is not hammered.</p>
 */
@Component
public class GeocodeClient {

    private static final Logger log = LoggerFactory.getLogger(GeocodeClient.class);

    static final String NOMINATIM = "https://nominatim.openstreetmap.org/";
    static final int CACHE_MAX = 10_000;
    /** Photon's public server is fair-use; space our calls so a burst stays polite. */
    static final long PHOTON_MIN_INTERVAL_MS = 200;

    private final ObjectMapper objectMapper;
    private final RestTemplate rest;
    private final Map<String, Entry> cache;
    private final Object photonLock = new Object();
    private long photonNextSlotMs = 0;

    @Value("${nominatim.user-agent:SitPrep/1.0 (sitprepcontact@gmail.com)}")
    private String userAgent;

    @Value("${geocode.photon-url:https://photon.komoot.io/api/}")
    private String photonUrl;

    public GeocodeClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.rest = new RestTemplate(factory);
        this.cache = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > CACHE_MAX;
            }
        };
    }

    // ── Cache ─────────────────────────────────────────────────────────

    /**
     * The value cached under {@code key}, else {@code load}'s result, kept for
     * {@code ttlOkMs} when non-null and {@code ttlMissMs} when null.
     */
    @SuppressWarnings("unchecked")
    public <T> T cached(String key, long ttlOkMs, long ttlMissMs, Supplier<T> load) {
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Entry e = cache.get(key);
            if (e != null && e.expiresAtMs > now) return (T) e.value;
        }
        T value = null;
        try {
            value = load.get();
        } catch (RuntimeException ex) {
            log.debug("geocode load failed for {}: {}", key, ex.getMessage());
        }
        long ttl = value != null ? ttlOkMs : ttlMissMs;
        synchronized (cache) {
            cache.put(key, new Entry(value, System.currentTimeMillis() + ttl));
        }
        return value;
    }

    int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    // ── Nominatim ─────────────────────────────────────────────────────

    /**
     * GET a Nominatim endpoint ({@code "search"} / {@code "reverse"}) with the
     * given query string (no leading '?'). Null on throttle, error or empty.
     */
    public JsonNode nominatim(String endpoint, String query) {
        if (!NominatimThrottle.acquire()) return null; // over 1 req/s: a miss, not a queue
        return getJson(URI.create(NOMINATIM + endpoint + "?" + query));
    }

    /** One-shot forward lookup: the first US match for {@code query}, or null. */
    public double[] nominatimFirst(String query) {
        if (query == null || query.isBlank()) return null;
        JsonNode root = nominatim("search",
                "format=jsonv2&limit=1&countrycodes=us&q=" + enc(query.trim()));
        if (root == null || !root.isArray() || root.isEmpty()) return null;
        double la = root.get(0).path("lat").asDouble(Double.NaN);
        double lo = root.get(0).path("lon").asDouble(Double.NaN);
        return Double.isFinite(la) && Double.isFinite(lo) ? new double[] { la, lo } : null;
    }

    // ── Photon (type-ahead) ───────────────────────────────────────────

    /** A type-ahead candidate: a readable US address label and its point. */
    public record Suggestion(String label, double lat, double lng) {}

    /**
     * Type-ahead: up to {@code limit} US address candidates for {@code query}.
     * Null when Photon could not be asked or did not answer (so the caller
     * caches a miss briefly, not an empty answer for hours).
     */
    public List<Suggestion> photonSearch(String query, int limit, Double nearLat, Double nearLng) {
        if (!photonSlot()) return null;
        // Ask for extra: Photon has no country filter, so non-US hits are dropped here.
        String bias = nearLat != null && nearLng != null
                ? String.format(Locale.US, "&lat=%.4f&lon=%.4f", nearLat, nearLng) : "";
        JsonNode root = getJson(URI.create(photonUrl + "?lang=en&limit=" + Math.min(limit * 3, 30)
                + bias + "&q=" + enc(query.trim())));
        return root == null ? null : parsePhoton(root, limit);
    }

    /** Photon GeoJSON → US suggestions with a composed label. Package-private for tests. */
    static List<Suggestion> parsePhoton(JsonNode root, int limit) {
        List<Suggestion> out = new ArrayList<>();
        if (root == null) return out;
        for (JsonNode f : root.path("features")) {
            JsonNode p = f.path("properties");
            if (!"US".equalsIgnoreCase(p.path("countrycode").asText(""))) continue;
            JsonNode c = f.path("geometry").path("coordinates");
            double lng = c.path(0).asDouble(Double.NaN);
            double lat = c.path(1).asDouble(Double.NaN);
            String label = photonLabel(p);
            if (label == null || !Double.isFinite(lat) || !Double.isFinite(lng)) continue;
            if (out.stream().anyMatch(s -> s.label().equals(label))) continue;
            out.add(new Suggestion(label, lat, lng));
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** "Name, 1450 N Traverse Ridge Rd, Draper, Utah 84020" — the parts Photon has. */
    static String photonLabel(JsonNode p) {
        String name = text(p, "name");
        String number = text(p, "housenumber");
        String street = text(p, "street");
        String city = firstText(p, "city", "town", "village", "district", "county");
        String state = text(p, "state");
        String postcode = text(p, "postcode");
        List<String> parts = new ArrayList<>();
        String line = street != null ? (number != null ? number + " " + street : street) : null;
        if (name != null && (line == null || !name.equalsIgnoreCase(line))) parts.add(name);
        if (line != null) parts.add(line);
        if (city != null && (name == null || !city.equalsIgnoreCase(name))) parts.add(city);
        String tail = state != null ? (postcode != null ? state + " " + postcode : state) : postcode;
        if (tail != null) parts.add(tail);
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private boolean photonSlot() {
        long wait;
        synchronized (photonLock) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, photonNextSlotMs);
            wait = slot - now;
            if (wait > 1_000) return false;
            photonNextSlotMs = slot + PHOTON_MIN_INTERVAL_MS;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    // ── HTTP ──────────────────────────────────────────────────────────

    private JsonNode getJson(URI uri) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set(HttpHeaders.USER_AGENT, userAgent);
            ResponseEntity<String> res = rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null && !res.getBody().isBlank()) {
                return objectMapper.readTree(res.getBody());
            }
        } catch (Exception e) {
            log.debug("geocode GET {} failed: {}", uri.getHost(), e.getMessage());
        }
        return null;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String s = text(node, f);
            if (s != null) return s;
        }
        return null;
    }

    /** Coordinates rounded to a bucket, for cache keys. */
    public static String bucket(String prefix, double lat, double lng, double q) {
        return String.format(Locale.US, "%s|%.4f|%.4f", prefix, Math.round(lat / q) * q, Math.round(lng / q) * q);
    }

    private record Entry(Object value, long expiresAtMs) {}
}
