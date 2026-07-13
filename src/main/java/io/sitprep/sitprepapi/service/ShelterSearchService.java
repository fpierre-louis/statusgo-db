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
    /** FEMA ESF#6 National Shelter System — currently-OPEN disaster shelters
     *  (ArcGIS MapServer; synced daily from the American Red Cross shelter DB,
     *  refreshed ~every 20 min). Replaces the retired OpenFEMA {@code OpenShelters}
     *  REST dataset the FE used to hit directly, which now 404s. */
    private static final String FEMA_NSS_OPEN_SHELTERS =
            "https://gis.fema.gov/arcgis/rest/services/NSS/OpenShelters/MapServer/0/query";

    private static final int DEFAULT_LIMIT = 10;
    // We cache a broader ranked candidate set (unfiltered) so the optional
    // pet/ADA filters can be applied per-request over the WHOLE radius —
    // not just whichever of the nearest 10 happen to be tagged. One cache
    // entry then serves every filter combination.
    private static final int CANDIDATE_LIMIT = 40;
    private static final double MAX_RADIUS_MI = 100.0;
    private static final double DEFAULT_RADIUS_MI = 40.0;

    private static final long TTL_OK_MS = Duration.ofHours(6).toMillis();
    private static final long TTL_FAIL_MS = Duration.ofMinutes(5).toMillis();
    /** FEMA NSS refreshes ~every 20 min; the national open set is small, so we
     *  cache it whole for 15 min and filter per-request rather than re-fetch. */
    private static final long TTL_FEMA_MS = Duration.ofMinutes(15).toMillis();
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
     * Optional {@code petFriendly} / {@code adaAccessible} restrict the
     * results to OSM-tagged matches across the whole radius (applied
     * before the top-N cut, not just within the nearest 10).
     *
     * @return up to {@code DEFAULT_LIMIT} shelters sorted nearest-first
     *         (dedicated shelters first); empty when nothing resolves.
     */
    public List<Shelter> search(Double lat, Double lng, String query, Double radiusMi,
                                boolean petFriendly, boolean adaAccessible) {
        double radius = clampRadius(radiusMi);

        Double anchorLat = lat;
        Double anchorLng = lng;
        if (anchorLat == null || anchorLng == null || !Double.isFinite(anchorLat) || !Double.isFinite(anchorLng)) {
            double[] geo = forwardGeocode(query);
            if (geo == null) return List.of();
            anchorLat = geo[0];
            anchorLng = geo[1];
        }

        // Cache the broad, UNFILTERED ranked candidate list; filter per-request.
        String key = String.format(Locale.US, "%.2f|%.2f|%.0f",
                Math.round(anchorLat / Q) * Q, Math.round(anchorLng / Q) * Q, radius);
        List<Shelter> candidates;
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            candidates = cached.shelters;
        } else {
            candidates = queryOverpass(anchorLat, anchorLng, radius);
            long ttl = candidates.isEmpty() ? TTL_FAIL_MS : TTL_OK_MS;
            cache.put(key, new CacheEntry(candidates, System.currentTimeMillis() + ttl));
        }

        // Apply optional filters (already ranked dedicated-first, nearest),
        // then take the top N.
        List<Shelter> result = new ArrayList<>();
        for (Shelter s : candidates) {
            if (petFriendly && !s.petFriendly()) continue;
            if (adaAccessible && !s.adaAccessible()) continue;
            result.add(s);
            if (result.size() >= DEFAULT_LIMIT) break;
        }
        return result;
    }

    /**
     * Currently-OPEN disaster shelters from FEMA's ESF#6 National Shelter System
     * (gis.fema.gov ArcGIS; synced daily from the American Red Cross shelter DB).
     * Complements {@link #search} (OSM permanent shelters): this is the
     * disaster-activated feed, legitimately near-empty in calm times. Replaces
     * the FE's retired direct OpenFEMA {@code OpenShelters} call (now 404).
     *
     * <p>The national open set is small, so it's cached whole (15 min) and
     * filtered per request by haversine distance. Never throws — empty list on
     * any upstream failure.</p>
     *
     * @param state optional 2-letter code for a server-side pre-filter
     * @return up to {@link #DEFAULT_LIMIT} open shelters within {@code radiusMi},
     *         nearest-first
     */
    public List<Shelter> openDisasterShelters(Double lat, Double lng, Double radiusMi, String state) {
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)) {
            return List.of();
        }
        double radius = clampRadius(radiusMi);
        String st = (state == null || state.isBlank()) ? null : state.trim().toUpperCase(Locale.US);

        String key = "fema|" + (st != null ? st : "US");
        List<Shelter> national;
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            national = cached.shelters;
        } else {
            national = fetchFemaOpenSheltersRaw(st);
            long ttl = national.isEmpty() ? TTL_FAIL_MS : TTL_FEMA_MS;
            cache.put(key, new CacheEntry(national, System.currentTimeMillis() + ttl));
        }

        // Cached rows carry distanceMi=0; recompute from THIS caller, filter, sort.
        List<Shelter> out = new ArrayList<>();
        for (Shelter s : national) {
            if (s.latitude() == null || s.longitude() == null) continue;
            double d = haversineMi(lat, lng, s.latitude(), s.longitude());
            if (d <= radius) out.add(withDistance(s, d));
        }
        out.sort((a, b) -> Double.compare(a.distanceMi(), b.distanceMi()));
        // Open shelters are sparse; return the whole nearby set (bounded) so the
        // FE map/rail can show them all rather than an arbitrary top-10.
        return out.size() > CANDIDATE_LIMIT ? out.subList(0, CANDIDATE_LIMIT) : out;
    }

    private static Shelter withDistance(Shelter s, double d) {
        return new Shelter(s.id(), s.name(), s.address(), s.city(), s.state(),
                s.latitude(), s.longitude(), s.phone(), s.petFriendly(), s.adaAccessible(),
                s.dedicated(), s.typeLabel(), s.source(), d);
    }

    private List<Shelter> fetchFemaOpenSheltersRaw(String state) {
        try {
            String where = (state != null) ? "state='" + state.replace("'", "''") + "'" : "1=1";
            String outFields = "shelter_id,shelter_name,address,city,state,zip,shelter_status,"
                    + "pet_accommodations_code,ada_compliant,wheelchair_accessible,latitude,longitude";
            URI uri = URI.create(FEMA_NSS_OPEN_SHELTERS
                    + "?where=" + URLEncoder.encode(where, StandardCharsets.UTF_8)
                    + "&outFields=" + URLEncoder.encode(outFields, StandardCharsets.UTF_8)
                    + "&returnGeometry=false&outSR=4326&f=json");
            JsonNode root = getJson(uri, MediaType.APPLICATION_JSON);
            if (root == null) return List.of();
            JsonNode features = root.path("features");
            if (!features.isArray()) return List.of();

            List<Shelter> out = new ArrayList<>();
            for (JsonNode f : features) {
                Shelter s = femaToShelter(f.path("attributes"));
                if (s != null) out.add(s);
            }
            return out;
        } catch (Exception e) {
            log.debug("FEMA NSS open-shelters fetch failed (state={}): {}", state, e.getMessage());
            return List.of();
        }
    }

    private static Shelter femaToShelter(JsonNode a) {
        if (a == null || a.isMissingNode()) return null;
        double la = a.path("latitude").asDouble(Double.NaN);
        double lo = a.path("longitude").asDouble(Double.NaN);
        if (!Double.isFinite(la) || !Double.isFinite(lo)) return null;

        String name = text(a, "shelter_name");
        if (name == null) name = "Open shelter";
        String city = text(a, "city");
        String state = text(a, "state");
        String zip = text(a, "zip");
        String street = text(a, "address");
        List<String> parts = new ArrayList<>();
        if (street != null) parts.add(street);
        if (city != null) parts.add(city);
        String stZip = ((state != null ? state + " " : "") + (zip != null ? zip : "")).trim();
        if (!stZip.isBlank()) parts.add(stZip);
        String address = String.join(", ", parts);

        boolean pets = isPetFriendly(text(a, "pet_accommodations_code"));
        boolean ada = "Y".equalsIgnoreCase(text(a, "ada_compliant"))
                || "Y".equalsIgnoreCase(text(a, "wheelchair_accessible"));

        String sid = text(a, "shelter_id");
        String id = "fema-nss-" + (sid != null ? sid
                : (Math.round(la * 1e4) + "-" + Math.round(lo * 1e4)));

        // These are human-verified, disaster-activated shelters → dedicated.
        return new Shelter(id, name, address, city, state, la, lo, "", pets, ada,
                true, null, "FEMA National Shelter System", 0.0);
    }

    /** NSS pet-accommodations is a lookup string ("NONE" / "UNK" / affirmative
     *  values). Treat only affirmative values as pet-friendly so an UNK/NONE
     *  never over-promises pet acceptance to a family relying on it. */
    private static boolean isPetFriendly(String code) {
        if (code == null) return false;
        String c = code.trim().toUpperCase(Locale.US);
        return !(c.isEmpty() || c.equals("NONE") || c.equals("UNK")
                || c.equals("N") || c.equals("NO") || c.equals("0"));
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
            return out.size() > CANDIDATE_LIMIT ? out.subList(0, CANDIDATE_LIMIT) : out;
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
