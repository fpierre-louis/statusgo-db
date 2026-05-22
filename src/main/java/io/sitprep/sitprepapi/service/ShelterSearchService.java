package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Free, key-less emergency-shelter search built entirely on OpenStreetMap
 * data — no Google Places (and no Google billing).
 *
 * <p>Two upstream calls, both free:
 * <ol>
 *   <li><b>Nominatim</b> ({@code /search}) forward-geocodes a city / zip
 *       string into a lat/lng anchor. Skipped when the caller already
 *       passes coordinates ("use my location").</li>
 *   <li><b>Overpass</b> pulls shelter-relevant OSM features within a
 *       radius of the anchor — {@code social_facility=shelter} (the
 *       homeless / emergency resource centers), {@code amenity=shelter}
 *       (filtered to drop picnic / transit shelters), {@code emergency=
 *       assembly_point|shelter}, and {@code amenity=community_centre}
 *       (commonly designated as emergency shelters).</li>
 * </ol>
 *
 * <p>Results are ranked by haversine distance and capped to the top N.
 * Both layers are cached in-memory (6 h on success) keyed on a coarse
 * lat/lng bucket so we respect the OSM operators' fair-use policy. Never
 * throws — returns an empty list on any upstream failure so the shelter
 * step degrades gracefully.</p>
 *
 * <p>Note: OSM shelter coverage varies by region; this is best-effort
 * community data, not an authoritative government registry. The
 * disaster-activated FEMA OpenShelters feed (surfaced separately on the
 * FE rail) complements it during active incidents.</p>
 */
@Service
public class ShelterSearchService {

    private static final Logger log = LoggerFactory.getLogger(ShelterSearchService.class);

    /** One shelter result. Jackson serializes the record directly to JSON.
     *  {@code dedicated} = an explicit shelter tag (vs. a "possible site"
     *  like a school / place of worship / library / community centre that
     *  is commonly designated but not a confirmed shelter). {@code typeLabel}
     *  is the human site type for possible sites (null for dedicated). */
    public record Shelter(
            String id,
            String name,
            String address,
            String city,
            String state,
            Double latitude,
            Double longitude,
            String phone,
            boolean petFriendly,
            boolean adaAccessible,
            boolean dedicated,
            String typeLabel,
            String source,
            double distanceMi
    ) {}

    private static final String NOMINATIM_SEARCH = "https://nominatim.openstreetmap.org/search";
    private static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";

    private static final int DEFAULT_LIMIT = 10;
    private static final double MAX_RADIUS_MI = 100.0;
    private static final double DEFAULT_RADIUS_MI = 40.0;

    private static final long TTL_OK_MS = Duration.ofHours(6).toMillis();
    private static final long TTL_FAIL_MS = Duration.ofMinutes(5).toMillis();
    /** ~0.02° ≈ 2 km buckets to coalesce nearby searches. */
    private static final double Q = 0.02;

    // amenity=shelter sub-types that are NOT emergency shelters — dropped.
    private static final Set<String> NON_EMERGENCY_SHELTER_TYPES = Set.of(
            "picnic_shelter", "public_transport", "gazebo", "lean_to",
            "rock_shelter", "sun_shelter", "weather_shelter", "basic_hut",
            "field_shelter"
    );

    private final ObjectMapper objectMapper;
    private final RestTemplate rest;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Value("${nominatim.user-agent:SitPrep/1.0 (contact@sitprep.app)}")
    private String userAgent;

    public ShelterSearchService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(25_000); // Overpass can be slow under load
        this.rest = new RestTemplate(factory);
    }

    /**
     * Search shelters near a coordinate, or near a free-text city / zip.
     * Pass either ({@code lat}, {@code lng}) OR a non-blank {@code query}.
     *
     * @return up to {@code DEFAULT_LIMIT} shelters sorted nearest-first;
     *         empty list when nothing resolves or upstream fails.
     */
    public List<Shelter> search(Double lat, Double lng, String query, Double radiusMi) {
        double radius = clampRadius(radiusMi);

        Double anchorLat = lat;
        Double anchorLng = lng;
        if (anchorLat == null || anchorLng == null || !Double.isFinite(anchorLat) || !Double.isFinite(anchorLng)) {
            double[] geo = forwardGeocode(query);
            if (geo == null) return List.of();
            anchorLat = geo[0];
            anchorLng = geo[1];
        }

        String key = String.format(Locale.US, "%.2f|%.2f|%.0f",
                Math.round(anchorLat / Q) * Q, Math.round(anchorLng / Q) * Q, radius);
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) return cached.shelters;

        List<Shelter> shelters = queryOverpass(anchorLat, anchorLng, radius);
        long ttl = shelters.isEmpty() ? TTL_FAIL_MS : TTL_OK_MS;
        cache.put(key, new CacheEntry(shelters, System.currentTimeMillis() + ttl));
        return shelters;
    }

    // ── Forward geocode (Nominatim /search) ─────────────────────────
    private double[] forwardGeocode(String query) {
        if (query == null || query.isBlank()) return null;
        try {
            URI uri = URI.create(NOMINATIM_SEARCH
                    + "?format=jsonv2&limit=1&countrycodes=us&q="
                    + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
            JsonNode root = getJson(uri, MediaType.APPLICATION_JSON);
            if (root != null && root.isArray() && root.size() > 0) {
                JsonNode first = root.get(0);
                double la = first.path("lat").asDouble(Double.NaN);
                double lo = first.path("lon").asDouble(Double.NaN);
                if (Double.isFinite(la) && Double.isFinite(lo)) return new double[]{la, lo};
            }
        } catch (Exception e) {
            log.debug("Nominatim forward-geocode failed for '{}': {}", query, e.getMessage());
        }
        return null;
    }

    // ── Overpass shelter query ──────────────────────────────────────
    private List<Shelter> queryOverpass(double lat, double lng, double radiusMi) {
        int radiusM = (int) Math.round(radiusMi * 1609.34);
        // Dedicated shelters can be worth a longer drive (full radius).
        // "Possible sites" (community centre / school / place of worship /
        // library) are only useful nearby and far more numerous, so cap
        // them to a tighter radius to stay relevant + keep the payload sane.
        int nearM = (int) Math.round(Math.min(radiusMi, 15.0) * 1609.34);
        String far = "(around:" + radiusM + "," + fmt(lat) + "," + fmt(lng) + ")";
        String near = "(around:" + nearM + "," + fmt(lat) + "," + fmt(lng) + ")";
        String ql = "[out:json][timeout:25];("
                + "nwr[\"social_facility\"=\"shelter\"]" + far + ";"
                + "nwr[\"amenity\"=\"shelter\"]" + far + ";"
                + "nwr[\"emergency\"=\"assembly_point\"]" + far + ";"
                + "nwr[\"emergency\"=\"shelter\"]" + far + ";"
                + "nwr[\"amenity\"=\"community_centre\"]" + near + ";"
                + "nwr[\"amenity\"=\"school\"]" + near + ";"
                + "nwr[\"amenity\"=\"place_of_worship\"]" + near + ";"
                + "nwr[\"amenity\"=\"library\"]" + near + ";"
                + ");out center 200;";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set(HttpHeaders.USER_AGENT, userAgent);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("data", ql);
            ResponseEntity<String> res = rest.postForEntity(OVERPASS_URL, new HttpEntity<>(form, headers), String.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) return List.of();

            JsonNode root = objectMapper.readTree(res.getBody());
            JsonNode elements = root.path("elements");
            if (!elements.isArray()) return List.of();

            List<Shelter> out = new ArrayList<>();
            for (JsonNode el : elements) {
                Shelter s = toShelter(el, lat, lng);
                if (s != null && s.distanceMi() <= radiusMi) out.add(s);
            }
            // Dedicated shelters always rank above "possible sites"; within
            // each tier, nearest first. Keeps real shelters from being
            // buried by the far-more-numerous schools / churches.
            out.sort((a, b) -> {
                if (a.dedicated() != b.dedicated()) return a.dedicated() ? -1 : 1;
                return Double.compare(a.distanceMi(), b.distanceMi());
            });
            return out.size() > DEFAULT_LIMIT ? out.subList(0, DEFAULT_LIMIT) : out;
        } catch (Exception e) {
            log.debug("Overpass shelter query failed at {},{}: {}", lat, lng, e.getMessage());
            return List.of();
        }
    }

    private Shelter toShelter(JsonNode el, double originLat, double originLng) {
        JsonNode tags = el.path("tags");
        String name = text(tags, "name");
        if (name == null) return null; // unnamed POIs aren't useful in a list

        // Drop non-emergency amenity=shelter sub-types (picnic, transit…).
        String amenity = text(tags, "amenity");
        if ("shelter".equals(amenity)) {
            String st = text(tags, "shelter_type");
            if (st != null && NON_EMERGENCY_SHELTER_TYPES.contains(st)) return null;
        }

        double la, lo;
        if (el.has("lat") && el.has("lon")) {
            la = el.path("lat").asDouble(Double.NaN);
            lo = el.path("lon").asDouble(Double.NaN);
        } else {
            JsonNode c = el.path("center");
            la = c.path("lat").asDouble(Double.NaN);
            lo = c.path("lon").asDouble(Double.NaN);
        }
        if (!Double.isFinite(la) || !Double.isFinite(lo)) return null;

        String city = text(tags, "addr:city");
        String state = text(tags, "addr:state");
        String address = buildAddress(tags, city, state);

        String phone = firstNonNull(text(tags, "phone"), text(tags, "contact:phone"));
        String wheelchair = text(tags, "wheelchair");
        boolean ada = "yes".equalsIgnoreCase(wheelchair) || "designated".equalsIgnoreCase(wheelchair);
        boolean pets = "yes".equalsIgnoreCase(text(tags, "dog"))
                || "yes".equalsIgnoreCase(text(tags, "pets"));

        // Classify: dedicated shelter (explicit tag) vs. "possible site"
        // (commonly designated but not confirmed — labeled so the user
        // knows the difference). Unknown matches are skipped.
        String social = text(tags, "social_facility");
        String emergency = text(tags, "emergency");
        boolean dedicated;
        String typeLabel = null;
        if ("shelter".equals(social) || "shelter".equals(amenity)
                || "assembly_point".equals(emergency) || "shelter".equals(emergency)) {
            dedicated = true;
        } else if ("community_centre".equals(amenity)) {
            dedicated = false; typeLabel = "Community center";
        } else if ("school".equals(amenity)) {
            dedicated = false; typeLabel = "School";
        } else if ("place_of_worship".equals(amenity)) {
            dedicated = false; typeLabel = "Place of worship";
        } else if ("library".equals(amenity)) {
            dedicated = false; typeLabel = "Library";
        } else {
            return null; // not a category we surface
        }

        String type = el.path("type").asText("node");
        long oid = el.path("id").asLong();
        String id = "osm-" + type + "-" + oid;

        double dist = haversineMi(originLat, originLng, la, lo);
        return new Shelter(id, name, address, city, state, la, lo, phone, pets, ada,
                dedicated, typeLabel, "OpenStreetMap", dist);
    }

    private static String buildAddress(JsonNode tags, String city, String state) {
        String hn = text(tags, "addr:housenumber");
        String street = text(tags, "addr:street");
        String zip = text(tags, "addr:postcode");
        List<String> parts = new ArrayList<>();
        String line1 = ((hn != null ? hn + " " : "") + (street != null ? street : "")).trim();
        if (!line1.isBlank()) parts.add(line1);
        if (city != null) parts.add(city);
        String stZip = ((state != null ? state + " " : "") + (zip != null ? zip : "")).trim();
        if (!stZip.isBlank()) parts.add(stZip);
        return parts.isEmpty() ? "" : String.join(", ", parts);
    }

    // ── HTTP / parse helpers ────────────────────────────────────────
    private JsonNode getJson(URI uri, MediaType accept) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(accept));
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

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static double clampRadius(Double radiusMi) {
        if (radiusMi == null || !Double.isFinite(radiusMi) || radiusMi <= 0) return DEFAULT_RADIUS_MI;
        return Math.min(radiusMi, MAX_RADIUS_MI);
    }

    private static String fmt(double d) {
        return String.format(Locale.US, "%.6f", d);
    }

    private static double haversineMi(double la1, double lo1, double la2, double lo2) {
        double R = 3958.8;
        double dLat = Math.toRadians(la2 - la1);
        double dLon = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private static final class CacheEntry {
        final List<Shelter> shelters;
        final long expiresAtMs;
        CacheEntry(List<Shelter> shelters, long expiresAtMs) {
            this.shelters = shelters;
            this.expiresAtMs = expiresAtMs;
        }
        boolean isExpired() { return System.currentTimeMillis() > expiresAtMs; }
    }
}
