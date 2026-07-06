package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.dto.MapPoiDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenStreetMap / Overpass API client — Community map Phase 2
 * (docs/COMMUNITY_API_GAMEPLAN.md §3.1-3.2). Fetches civic amenities inside a
 * bounding box and normalizes them to the canonical {@link MapPoiDto}. All
 * calls go through the L2 cache in {@link ExternalPoiCacheService}; this class
 * only knows how to talk to Overpass + shape the response.
 *
 * <p>Overpass is a shared free service — we send a real {@code User-Agent},
 * cap results, use a bounded timeout, and fail SOFT (return empty on any error)
 * so a slow/unavailable upstream never breaks the map request.</p>
 */
@Service
public class OverpassService {

    private static final Logger log = LoggerFactory.getLogger(OverpassService.class);

    // Public mirror. (If rate-limited, kumi.systems / overpass.kumi.systems is a
    // drop-in alternate; wire a fallback list in a later pass if needed.)
    private static final String ENDPOINT = "https://overpass-api.de/api/interpreter";
    private static final int MAX_RESULTS = 200;
    private static final String USER_AGENT = "SitPrep/1.0 (+https://sitprep.app; community map)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper mapper;

    public OverpassService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Fetch parks + schools + community/public buildings inside (S,W,N,E),
     * normalized to MapPoi. Returns an empty list on any failure (soft-fail).
     * lat/lng are populated; {@code distanceKm} is left null — the caller
     * recomputes it from the actual request center on read.
     */
    public List<MapPoiDto> fetch(double south, double west, double north, double east) {
        String ql = buildQuery(south, west, north, east);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "data=" + URLEncoder.encode(ql, StandardCharsets.UTF_8)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                // warn without the bbox (viewer-derived coordinates); the
                // full box is available at debug for diagnosis.
                log.warn("Overpass non-200 ({})", res.statusCode());
                log.debug("Overpass non-200 ({}) for bbox {},{},{},{}", res.statusCode(), south, west, north, east);
                return List.of();
            }
            return parse(res.body());
        } catch (Exception e) {
            log.warn("Overpass fetch failed: {}", e.toString());
            log.debug("Overpass fetch failed for bbox {},{},{},{}: {}", south, west, north, east, e.toString());
            return List.of();
        }
    }

    /**
     * Overpass QL. {@code nwr} = node+way+relation; {@code out center} gives
     * ways/relations a single center coordinate so we don't compute centroids.
     * Bounding box is (south,west,north,east) per Overpass convention.
     */
    private static String buildQuery(double s, double w, double n, double e) {
        String bbox = "(" + s + "," + w + "," + n + "," + e + ")";
        return "[out:json][timeout:25];"
                + "("
                + "nwr[\"leisure\"=\"park\"]" + bbox + ";"
                + "nwr[\"amenity\"=\"school\"]" + bbox + ";"
                + "nwr[\"amenity\"=\"community_centre\"]" + bbox + ";"
                + "nwr[\"amenity\"=\"public_building\"]" + bbox + ";"
                + ");"
                + "out center " + MAX_RESULTS + ";";
    }

    private List<MapPoiDto> parse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode elements = root.path("elements");
        if (!elements.isArray()) return List.of();

        List<MapPoiDto> out = new ArrayList<>();
        for (JsonNode el : elements) {
            JsonNode tags = el.path("tags");
            String name = tags.path("name").asText(null);
            if (name == null || name.isBlank()) continue; // quality: named POIs only

            // Coordinate: nodes carry lat/lon; ways/relations carry center{lat,lon}.
            double lat, lng;
            if (el.has("lat") && el.has("lon")) {
                lat = el.get("lat").asDouble();
                lng = el.get("lon").asDouble();
            } else if (el.has("center")) {
                lat = el.get("center").path("lat").asDouble(Double.NaN);
                lng = el.get("center").path("lon").asDouble(Double.NaN);
            } else {
                continue;
            }
            if (Double.isNaN(lat) || Double.isNaN(lng)) continue;

            String leisure = tags.path("leisure").asText(null);
            String amenity = tags.path("amenity").asText(null);
            String family;
            String category;
            if ("park".equals(leisure)) {
                family = "park";
                category = "park";
            } else if (amenity != null && !amenity.isBlank()) {
                family = "amenity";       // schools + community/public buildings
                category = amenity;       // school | community_centre | public_building
            } else {
                continue;
            }

            String website = firstNonBlank(
                    tags.path("website").asText(null),
                    tags.path("contact:website").asText(null));
            String id = "overpass:" + el.path("type").asText("node") + "/" + el.path("id").asLong();
            String mapUrl = "https://www.google.com/maps/search/?api=1&query=" + lat + "," + lng;

            out.add(new MapPoiDto(
                    id, family, "overpass", name,
                    lat, lng, null,                 // distanceKm recomputed on read
                    null, null, null, null, null,   // group/agency fields
                    null, null, null, null,         // aid fields
                    category, website, mapUrl,
                    "© OpenStreetMap contributors"  // attribution — REQUIRED for external
            ));
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
