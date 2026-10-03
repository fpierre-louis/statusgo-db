package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Free, key-less address geocoding for the FE address fields — the type-ahead
 * and "use my current location" — so we carry no Google Places billing.
 *
 * <ul>
 *   <li>{@link #forwardSearch} — type-ahead: free text → ranked US address
 *       suggestions, from <b>Photon</b> (built for search-as-you-type; the
 *       Nominatim policy forbids autocomplete on its public server).</li>
 *   <li>{@link #reverse} — coordinates → one best full-address label, from
 *       Nominatim (one-shot, throttled).</li>
 * </ul>
 *
 * <p>All HTTP, throttling and caching live in {@link GeocodeClient}. Never
 * throws — an empty list / null on any upstream failure.</p>
 *
 * <p>Distinct from {@link NominatimGeocodeService} (which resolves a coarse
 * {city, region, state} {@code Place} for feed labels) — this one deals in full
 * street-address strings + multiple forward candidates.</p>
 */
@Service
public class GeocodeService {

    /** A geocode candidate: a readable full address and its point. */
    public record Suggestion(String label, Double lat, Double lng) {}

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 8;
    private static final long TTL_OK_MS = Duration.ofHours(6).toMillis();
    private static final long TTL_MISS_MS = Duration.ofMinutes(5).toMillis();
    /** ~0.0005° ≈ 55 m buckets — fine enough that a "use my location" fix
     *  resolves to the right building, coarse enough to coalesce repeats. */
    private static final double Q = 0.0005;

    private final GeocodeClient client;

    public GeocodeService(GeocodeClient client) {
        this.client = client;
    }

    /** Forward type-ahead: free text → up to {@code limit} US address candidates. */
    public List<Suggestion> forwardSearch(String query, Integer limit) {
        return forwardSearch(query, limit, null, null);
    }

    /**
     * As above, ranking matches near ({@code nearLat}, {@code nearLng}) first
     * when given — without it "Traverse Ridge" finds Ontario before Draper.
     */
    public List<Suggestion> forwardSearch(String query, Integer limit, Double nearLat, Double nearLng) {
        if (query == null || query.isBlank() || query.trim().length() < 3) return List.of();
        int lim = (limit == null || limit < 1) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String q = query.trim().toLowerCase(Locale.US);
        boolean near = nearLat != null && nearLng != null && Double.isFinite(nearLat) && Double.isFinite(nearLng);
        // ~11 km bias buckets: close enough to rank the same, coarse enough to share.
        String nearKey = near ? GeocodeClient.bucket("", nearLat, nearLng, 0.1) : "-";
        List<GeocodeClient.Suggestion> hits = client.cached("photon|" + q + "|" + lim + "|" + nearKey,
                TTL_OK_MS, TTL_MISS_MS,
                () -> client.photonSearch(q, lim, near ? nearLat : null, near ? nearLng : null));
        if (hits == null) return List.of();
        return hits.stream().map(h -> new Suggestion(h.label(), h.lat(), h.lng())).toList();
    }

    /** Reverse: coordinates → single best full-address label, or null. */
    public Suggestion reverse(Double lat, Double lng) {
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)) return null;
        return client.cached(GeocodeClient.bucket("addr", lat, lng, Q), TTL_OK_MS, TTL_MISS_MS, () -> {
            // Building level first; a coarser zoom when the point is between buildings.
            for (int zoom : new int[] { 18, 17, 16 }) {
                JsonNode root = client.nominatim("reverse", String.format(Locale.US,
                        "format=jsonv2&addressdetails=1&zoom=%d&lat=%.6f&lon=%.6f", zoom, lat, lng));
                String label = GeocodeClient.text(root, "display_name");
                if (label != null) return new Suggestion(label, lat, lng);
                if (root == null) return null; // throttled or down: don't spend more slots
            }
            return null;
        });
    }
}
