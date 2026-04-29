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
        // Prime in a separate thread so a slow NWS doesn't block app
        // startup. If the prime fails, the @Scheduled tick will retry.
        new Thread(() -> {
            try { pollNws(); } catch (Exception ignored) {}
        }, "alert-ingest-prime").start();
    }

    /**
     * Poll NWS every 5 minutes. {@code fixedDelay} (not {@code fixedRate})
     * so a slow poll doesn't queue up another. {@code initialDelay} of
     * 60s lets the @PostConstruct prime finish first.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void scheduledPoll() {
        try {
            pollNws();
        } catch (Exception e) {
            log.warn("AlertIngest: scheduled poll failed: {}", e.getMessage());
        }
    }

    private void pollNws() throws Exception {
        long started = System.currentTimeMillis();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(NWS_ACTIVE_URL))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/geo+json")
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            log.warn("AlertIngest: NWS returned {} ({}ms). Keeping previous snapshot.",
                    code, System.currentTimeMillis() - started);
            return;
        }

        JsonNode root = json.readTree(resp.body());
        JsonNode features = root.path("features");
        if (!features.isArray()) {
            log.warn("AlertIngest: NWS response had no 'features' array. " +
                    "Keeping previous snapshot.");
            return;
        }

        List<NormalizedAlert> normalized = new ArrayList<>(features.size());
        Iterator<JsonNode> it = features.elements();
        while (it.hasNext()) {
            JsonNode f = it.next();
            try {
                normalized.add(normalize(f));
            } catch (Exception ex) {
                // Skip individual feature parse errors — don't drop the
                // whole batch because one alert was malformed.
                log.debug("AlertIngest: skipped malformed NWS feature: {}", ex.getMessage());
            }
        }

        Snapshot next = new Snapshot(
                List.copyOf(normalized),
                Instant.now(),
                Instant.now()
        );
        latest.set(next);

        log.info("AlertIngest: NWS poll OK — {} alerts ingested in {}ms",
                normalized.size(), System.currentTimeMillis() - started);
    }

    private NormalizedAlert normalize(JsonNode f) {
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

    /** Read-only access for the AlertResource. */
    public Snapshot getSnapshot() {
        return latest.get();
    }

    /**
     * Manual refresh hook. Used by a forthcoming
     * {@code POST /api/alerts/refresh} dev-only endpoint to bypass the
     * 5-minute cadence during testing. Catches its own errors so callers
     * can fire-and-forget.
     */
    public void refreshNow() {
        try { pollNws(); } catch (Exception e) {
            log.warn("AlertIngest: manual refresh failed: {}", e.getMessage());
        }
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
