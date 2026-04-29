package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls public emergency-alert sources on a fixed schedule and exposes a
 * consolidated, cached snapshot to the rest of the app via
 * {@link #getSnapshot()}.
 *
 * <p>Phase 1 of the alert backend (per docs/ALERTS_INTEGRATION.md build
 * order step 5). Today: NWS active alerts only, in-memory cache, single
 * server-wide snapshot. Phase 2 adds USGS, persistence (AlertSubscription
 * + AlertPost), geocell-scoped dedup, and STOMP-broadcast auto-posts.</p>
 *
 * <p><b>Why centralize:</b> the FE was hitting NWS once per page-load via
 * {@code fetchEmergencySnapshot} on the FemaWeatherMVP, MapView, and
 * CrisisBand surfaces. With ~25 beta testers across N pages each, that
 * was N×25 NWS calls per session. NWS is rate-limited per-IP — a single
 * server-side poll every 5min keeps us well under budget regardless of
 * frontend traffic, and the cache is shared across all users.</p>
 *
 * <p><b>Failure mode:</b> on poll error (network, NWS 5xx, JSON shape
 * change), we keep serving the previous snapshot. The {@link Snapshot}
 * carries {@code lastSuccessAt} so the FE can render a "last updated"
 * affordance and decide whether to fall back to its own direct NWS call
 * if the server-side data is stale.</p>
 */
@Service
public class AlertIngestService {

    private static final Logger log = LoggerFactory.getLogger(AlertIngestService.class);

    /** All active US alerts, single GET. Returns a GeoJSON FeatureCollection. */
    private static final String NWS_ACTIVE_URL = "https://api.weather.gov/alerts/active";

    /**
     * USGS recent significant quakes — M4.5+ in the last 24h, global.
     * Tractable size (typically 5-30 features). Lower magnitudes flood the
     * cache with non-actionable signals; consumers can still get more via
     * USGS direct query if they need it.
     */
    private static final String USGS_RECENT_URL =
            "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson";

    /** NWS asks for a User-Agent identifying the consumer. */
    private static final String USER_AGENT =
            "(SitPrep/sitprep.app, contactus@sitprep.app)";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Latest snapshot. AtomicReference so the scheduled writer and the
     * resource-thread readers don't need a lock — they swap whole
     * snapshot objects.
     */
    private final AtomicReference<Snapshot> latest =
            new AtomicReference<>(Snapshot.empty());

    /**
     * Force a poll on startup so the first request after boot has data
     * to serve. Without this, the cache is empty until the first
     * scheduled tick (5 min after boot).
     */
    @PostConstruct
    public void primeOnStartup() {
        // Prime in a separate thread so a slow upstream doesn't block app
        // startup. If a prime fails, the @Scheduled tick will retry.
        new Thread(() -> {
            try { refreshNow(); } catch (Exception ignored) {}
        }, "alert-ingest-prime").start();
    }

    /**
     * Poll NWS + USGS every 5 minutes. {@code fixedDelay} (not
     * {@code fixedRate}) so a slow poll doesn't queue up another.
     * {@code initialDelay} of 60s lets the @PostConstruct prime finish
     * first. Both polls run sequentially in this method — they're cheap
     * enough that parallelism isn't worth the extra complexity, and a
     * sequential failure is easier to reason about in logs.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void scheduledPoll() {
        refreshNow();
    }

    /**
     * Run both upstream polls and merge results into a single snapshot.
     * Each source is independent — if NWS fails but USGS succeeds we
     * still update with the USGS half (and vice versa), preserving the
     * other source's last-good data.
     */
    private void pollAll() {
        // Each source updates the snapshot independently so a failure in
        // one doesn't drop the other.
        Snapshot prev = latest.get();

        List<NormalizedAlert> nws;
        try {
            nws = pollNws();
        } catch (Exception e) {
            log.warn("AlertIngest: NWS poll failed: {}", e.getMessage());
            // Keep previous NWS data
            nws = prev.alerts.stream().filter(a -> "NWS".equals(a.source())).toList();
        }

        List<NormalizedAlert> usgs;
        try {
            usgs = pollUsgs();
        } catch (Exception e) {
            log.warn("AlertIngest: USGS poll failed: {}", e.getMessage());
            usgs = prev.alerts.stream().filter(a -> "USGS".equals(a.source())).toList();
        }

        List<NormalizedAlert> merged = new ArrayList<>(nws.size() + usgs.size());
        merged.addAll(nws);
        merged.addAll(usgs);

        Snapshot next = new Snapshot(
                List.copyOf(merged),
                Instant.now(),
                Instant.now()
        );
        latest.set(next);
    }

    private List<NormalizedAlert> pollNws() throws Exception {
        long started = System.currentTimeMillis();
        JsonNode root = fetchJson(NWS_ACTIVE_URL, "application/geo+json");
        JsonNode features = root.path("features");
        if (!features.isArray()) {
            log.warn("AlertIngest: NWS response had no 'features' array; " +
                    "treating as empty for this tick.");
            return List.of();
        }

        List<NormalizedAlert> normalized = new ArrayList<>(features.size());
        Iterator<JsonNode> it = features.elements();
        while (it.hasNext()) {
            JsonNode f = it.next();
            try {
                normalized.add(normalizeNws(f));
            } catch (Exception ex) {
                // Skip individual feature parse errors — don't drop the
                // whole batch because one alert was malformed.
                log.debug("AlertIngest: skipped malformed NWS feature: {}", ex.getMessage());
            }
        }

        log.info("AlertIngest: NWS poll OK — {} alerts ingested in {}ms",
                normalized.size(), System.currentTimeMillis() - started);
        return normalized;
    }

    private List<NormalizedAlert> pollUsgs() throws Exception {
        long started = System.currentTimeMillis();
        JsonNode root = fetchJson(USGS_RECENT_URL, "application/geo+json");
        JsonNode features = root.path("features");
        if (!features.isArray()) {
            log.warn("AlertIngest: USGS response had no 'features' array.");
            return List.of();
        }

        List<NormalizedAlert> normalized = new ArrayList<>(features.size());
        Iterator<JsonNode> it = features.elements();
        while (it.hasNext()) {
            JsonNode f = it.next();
            try {
                normalized.add(normalizeUsgs(f));
            } catch (Exception ex) {
                log.debug("AlertIngest: skipped malformed USGS feature: {}", ex.getMessage());
            }
        }

        log.info("AlertIngest: USGS poll OK — {} quakes ingested in {}ms",
                normalized.size(), System.currentTimeMillis() - started);
        return normalized;
    }

    /**
     * Shared HTTP fetch + JSON parse. Throws on non-2xx so the caller's
     * error path (preserve previous snapshot) fires.
     */
    private JsonNode fetchJson(String url, String accept) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept + ", application/json;q=0.9, */*;q=0.8")
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("Upstream " + url + " returned HTTP " + code);
        }
        return json.readTree(resp.body());
    }

    private NormalizedAlert normalizeNws(JsonNode f) {
        JsonNode p = f.path("properties");
        String id = textOrNull(p, "id");
        if (id == null) id = textOrNull(f, "id");

        String headline = textOrNull(p, "headline");
        if (headline == null) {
            String event = textOrNull(p, "event");
            String area = textOrNull(p, "areaDesc");
            headline = (event != null && area != null) ? event + " — " + area
                    : (event != null) ? event
                    : "Weather Alert";
        }

        // Geometry as raw map so it serializes back to GeoJSON. NWS may
        // return null geometry for alerts without polygons (e.g. some
        // SAME-code-only zones); we pass through as-is.
        JsonNode geomNode = f.path("geometry");
        Object geometry = geomNode.isMissingNode() || geomNode.isNull()
                ? null
                : json.convertValue(geomNode, Map.class);

        return new NormalizedAlert(
                id,
                "NWS",
                textOrNull(p, "severity"),
                headline,
                textOrNull(p, "description"),
                textOrNull(p, "areaDesc"),
                isoOrNull(p, "onset", "effective", "sent"),
                isoOrNull(p, "ends", "expires"),
                geometry
        );
    }

    /**
     * USGS quake → NormalizedAlert. Severity is derived from magnitude
     * (M6+ Severe, M5+ Moderate, else Minor) so consumers (CrisisBand
     * filter, AlertPost dispatcher) can route by severity without
     * knowing about magnitudes specifically.
     */
    private NormalizedAlert normalizeUsgs(JsonNode f) {
        JsonNode p = f.path("properties");
        String id = textOrNull(f, "id");

        double mag = p.path("mag").asDouble(0.0);
        String place = textOrNull(p, "place");
        String headline = String.format(
                "M%.1f — %s",
                mag,
                place != null ? place : "Earthquake"
        );

        String severity =
                mag >= 6.0 ? "Severe" :
                mag >= 5.0 ? "Moderate" :
                "Minor";

        // USGS time is epoch-millis. Convert to ISO-8601 string for
        // schema parity with NWS.
        String startedAt = null;
        long timeMs = p.path("time").asLong(0L);
        if (timeMs > 0) startedAt = Instant.ofEpochMilli(timeMs).toString();

        // Quake geometry is a Point [lon, lat, depth].
        JsonNode geomNode = f.path("geometry");
        Object geometry = geomNode.isMissingNode() || geomNode.isNull()
                ? null
                : json.convertValue(geomNode, Map.class);

        return new NormalizedAlert(
                id,
                "USGS",
                severity,
                headline,
                textOrNull(p, "title"),
                place,
                startedAt,
                /* endsAt */ null, // quakes are point-in-time
                geometry
        );
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("");
        return s.isEmpty() ? null : s;
    }

    /**
     * Try several timestamp fields in order; return the first one that
     * parses. NWS uses {@code onset|effective|sent} for start and
     * {@code ends|expires} for end with field availability varying by
     * alert type.
     */
    private static String isoOrNull(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = textOrNull(n, f);
            if (v != null) return v;
        }
        return null;
    }

    /** Read-only access for the AlertResource — full snapshot, no filter. */
    public Snapshot getSnapshot() {
        return latest.get();
    }

    /**
     * Filtered snapshot: only alerts whose geometry's centroid falls
     * within {@code radiusMi} of {@code (lat, lng)}. Alerts with no
     * geometry are included unconditionally (they may apply broadly —
     * e.g. SAME-code NWS zones without polygon data, FEMA recovery
     * declarations once Phase 2 ingests them).
     *
     * <p>Coarse filter — uses the alert geometry's first vertex (Point
     * for quakes; first ring vertex for polygons) rather than a true
     * point-in-polygon test. Adequate for cutting a 387-alert NWS feed
     * down to the dozen relevant to a user's area; precise filtering
     * happens client-side via Leaflet's geometry rendering.</p>
     */
    public Snapshot getSnapshotForPoint(double lat, double lng, double radiusMi) {
        Snapshot s = latest.get();
        double radiusKm = radiusMi * 1.609344;
        List<NormalizedAlert> filtered = new ArrayList<>();
        for (NormalizedAlert a : s.alerts) {
            double[] coord = firstCoord(a.geometry);
            if (coord == null) {
                // Geometry-less alert: include by default. Coarse but safe —
                // these are usually broad-impact (e.g. statewide advisories).
                filtered.add(a);
                continue;
            }
            double distKm = haversineKm(lat, lng, coord[1], coord[0]);
            if (distKm <= radiusKm) filtered.add(a);
        }
        return new Snapshot(List.copyOf(filtered), Instant.now(), s.lastSuccessAt);
    }

    /**
     * Pull the first [lon, lat] coordinate from a GeoJSON geometry. Handles
     * Point (USGS quakes) + Polygon (NWS warnings) + MultiPolygon. Returns
     * null when the structure isn't recognized.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static double[] firstCoord(Object geom) {
        if (!(geom instanceof Map)) return null;
        Map m = (Map) geom;
        Object type = m.get("type");
        Object coords = m.get("coordinates");
        if (!(type instanceof String) || coords == null) return null;
        try {
            switch ((String) type) {
                case "Point":
                    // [lon, lat] (or [lon, lat, depth] for quakes)
                    List<Number> p = (List<Number>) coords;
                    return new double[] { p.get(0).doubleValue(), p.get(1).doubleValue() };
                case "Polygon": {
                    // [[ [lon,lat], [lon,lat], ... ]]
                    List<List<List<Number>>> rings = (List<List<List<Number>>>) coords;
                    List<Number> v = rings.get(0).get(0);
                    return new double[] { v.get(0).doubleValue(), v.get(1).doubleValue() };
                }
                case "MultiPolygon": {
                    // [[[ [lon,lat], ... ]]]
                    List<List<List<List<Number>>>> polys = (List<List<List<List<Number>>>>) coords;
                    List<Number> v = polys.get(0).get(0).get(0);
                    return new double[] { v.get(0).doubleValue(), v.get(1).doubleValue() };
                }
                default:
                    return null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** Great-circle distance in km. R = 6371. */
    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    /**
     * Manual refresh hook. Used by {@code POST /api/alerts/refresh} to
     * bypass the 5-minute cadence during testing. Polls both sources
     * synchronously then merges into the cache.
     */
    public void refreshNow() {
        pollAll();
    }

    // -------------------------------------------------------------------
    // DTOs returned to the resource layer
    // -------------------------------------------------------------------

    /** Cached alert payload + freshness markers. */
    public record Snapshot(
            List<NormalizedAlert> alerts,
            /** When this snapshot was assembled (always non-null). */
            Instant generatedAt,
            /** When the last successful upstream poll completed (null until first success). */
            Instant lastSuccessAt
    ) {
        public static Snapshot empty() {
            return new Snapshot(List.of(), Instant.now(), null);
        }
    }

    /**
     * Schema mirrors the FE's emergencyApis.js normalized shape so the
     * frontend can swap from its own NWS calls to this endpoint without
     * touching its render code. Keep these field names stable.
     */
    public record NormalizedAlert(
            String id,
            String source,
            String severity,
            String headline,
            String description,
            String area,
            String startedAt,
            String endsAt,
            /** Raw GeoJSON geometry (map of {type, coordinates}), or null. */
            Object geometry
    ) {}
}
