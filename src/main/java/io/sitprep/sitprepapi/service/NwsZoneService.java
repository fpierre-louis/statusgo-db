package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves NWS <b>UGC zone codes</b> — the targeting key 82% of alerts use
 * instead of a polygon.
 *
 * <h2>Why this exists (audit P0-4, unblocks P1-1)</h2>
 *
 * <p>Measured against the live feed on 2026-08-22: <b>254 of 310 active NWS
 * alerts (82%) have {@code geometry: null}</b>, and so do <b>40 of the 75
 * Severe ones (53%)</b> — every Extreme Heat Warning, Red Flag Warning, Flood
 * Watch and Air Quality Alert in the country. They are not missing their
 * location; they are targeted by <b>UGC zone code</b> rather than by polygon,
 * which is how NWS ships long-duration area-wide products. Short-fuse
 * storm-based products (Severe Thunderstorm, Flash Flood, Flood Warning) are
 * the ones that carry polygons.</p>
 *
 * <p>Because the code path only understood polygons, those 254 alerts were
 * <b>broadcast to everyone</b> on the read path ({@code getSnapshotForPoint}
 * included geometry-less alerts unconditionally — 554 rows / 307 KB to a Salt
 * Lake City user, exactly one of them actually near) and <b>silently dropped
 * on the dispatch path</b> ({@code dispatchOnce} skips an alert with no
 * coordinate). One fix, both problems.</p>
 *
 * <h2>Why zone-code matching and not polygon storage</h2>
 *
 * <p><b>Every alert carries UGC — verified 100% of 310, including 100% of the
 * 254 geometry-less ones.</b> So the match is a set intersection between the
 * alert's codes and the codes covering the user's coordinate, and needs no
 * geometry at all:</p>
 *
 * <pre>
 *   user (33.3172, -110.5297) -> {AZZ560, AZC007, AZZ133}
 *   Extreme Heat Warning      -> {AZZ560}                  -> MATCH
 *   Portland ME user          -> {MEZ024, MEC005, MEZ110}  -> no match
 * </pre>
 *
 * <p>The alternative — storing zone polygons — is the wrong shape. A single
 * zone runs 45 KB–1.6 MB of coordinates (AZZ509 is 16,022 vertices), and there
 * are ~4,000 US zones. This service fetches a polygon <b>only</b> to derive a
 * centroid for dispatch placement, keeps the 16 bytes, and discards the rest.</p>
 *
 * <h2>Caching</h2>
 *
 * <p>Zone boundaries are static — NWS itself answers
 * {@code cache-control: max-age=2591999} (30 days) on the zone endpoint. Both
 * caches are effectively permanent for the life of the process and bounded by
 * the number of US zones, so there is no eviction policy beyond a size cap.</p>
 *
 * <p>The caches are per-instance and in-memory, which means a cold start
 * re-resolves. That is <b>deliberate and safe here</b>, unlike the equivalent
 * property of {@link NominatimGeocodeService} (audit P1-9): a miss in this
 * service degrades to the state-prefix fallback in
 * {@code AlertIngestService}, never to a dropped alert, and the warm path is
 * driven off a background executor rather than a request thread.</p>
 */
@Service
public class NwsZoneService {

    private static final Logger log = LoggerFactory.getLogger(NwsZoneService.class);

    private static final String POINTS_URL = "https://api.weather.gov/points/%.4f,%.4f";
    private static final String ZONE_URL = "https://api.weather.gov/zones/%s/%s";

    /** Same identification NWS asks for as {@code AlertIngestService}. */
    private static final String USER_AGENT = "(SitPrep/sitprep.app, contactus@sitprep.app)";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Bound on both caches. ~4,000 public + ~1,000 fire + ~3,200 county zones
     * nationwide, so 12k covers the whole country with headroom; the cap only
     * exists so a pathological input can't grow the map without limit.
     */
    private static final int MAX_CACHE_ENTRIES = 12_000;

    /**
     * Ceiling on centroid fetches queued per ingest tick.
     *
     * <p>Without a cap, the first prime after a cold start queues one fetch
     * per unresolved zone in the national feed — measured at <b>909</b> before
     * this bound existed, which is ~3 minutes of continuous requests against
     * api.weather.gov triggered by a dyno restart. That is the same
     * unthrottled-loop shape the audit flagged for Nominatim (P1-9), and
     * reintroducing it here would have been the same mistake in a new
     * file.</p>
     *
     * <p>At 100/tick a cold start fills the working set in two or three
     * 5-minute ticks. Nothing waits on it: the read path never needs a
     * centroid, and dispatch retries every tick.</p>
     */
    private static final int MAX_WARM_PER_TICK = 100;

    /** Spacing between centroid fetches, so a warm burst stays polite. */
    private static final long WARM_SPACING_MS = 250;

    /**
     * Point→zone cache key precision. 3 decimal places ≈ 110 m, comfortably
     * finer than any zone boundary, so two users in the same neighbourhood
     * share one lookup without risking a wrong-side-of-the-line answer.
     */
    private static final String POINT_KEY_FMT = "%.3f,%.3f";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper json = new ObjectMapper();

    /** point bucket -> UGC codes covering it. */
    private final Map<String, Set<String>> pointZones = new ConcurrentHashMap<>();

    /** UGC code -> {lat, lng} centroid. Present only once warmed. */
    private final Map<String, double[]> zoneCentroids = new ConcurrentHashMap<>();

    /** UGC codes already attempted (success or failure) so warm() doesn't retry in a loop. */
    private final Set<String> centroidAttempted = ConcurrentHashMap.newKeySet();

    /**
     * Single-threaded so zone warming can never fan out into a burst against
     * api.weather.gov, and so the transient parse cost of one 45 KB–1.6 MB
     * polygon is never multiplied across threads on a memory-bounded dyno.
     */
    private final ExecutorService warmPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nws-zone-warm");
        t.setDaemon(true);
        return t;
    });

    @Value("${alerts.zones.enabled:true}")
    private boolean enabled = true;

    @PreDestroy
    void shutdown() {
        warmPool.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Point -> zone codes (the read path)
    // ------------------------------------------------------------------

    /**
     * The UGC codes covering a coordinate: its public forecast zone, its
     * county zone, and its fire-weather zone. An alert matches this point when
     * any of its own UGC codes is in this set.
     *
     * <p>All three are needed. A Red Flag Warning targets fire zones
     * ({@code ORZ691}), an Extreme Heat Warning targets public zones
     * ({@code AZZ560}), and county-based products target county zones
     * ({@code AZC007}) — matching only one kind would silently miss the other
     * two.</p>
     *
     * <p>Returns an empty set when the lookup fails or the coordinate is
     * outside NWS coverage. <b>Callers must treat empty as "unknown", never as
     * "no alerts apply"</b> — see the fallback ladder in
     * {@code AlertIngestService.getSnapshotForPoint}.</p>
     */
    public Set<String> zoneCodesForPoint(double lat, double lng) {
        if (!enabled) return Set.of();
        String key = String.format(Locale.ROOT, POINT_KEY_FMT, lat, lng);
        Set<String> cached = pointZones.get(key);
        if (cached != null) return cached;

        Set<String> codes;
        try {
            codes = fetchZoneCodes(lat, lng);
        } catch (Exception e) {
            log.debug("NwsZone: point lookup failed at ({}, {}): {}", lat, lng, e.getMessage());
            // Do NOT cache a failure as an empty set — a transient upstream
            // blip would then pin this coordinate to "unknown" for the life of
            // the process, and the fallback is coarser than the real answer.
            return Set.of();
        }

        if (pointZones.size() < MAX_CACHE_ENTRIES) {
            pointZones.put(key, codes);
        }
        return codes;
    }

    private Set<String> fetchZoneCodes(double lat, double lng) throws Exception {
        JsonNode root = fetchJson(String.format(Locale.ROOT, POINTS_URL, lat, lng));
        JsonNode p = root.path("properties");
        Set<String> codes = new LinkedHashSet<>(4);
        for (String field : List.of("forecastZone", "county", "fireWeatherZone")) {
            String ugc = ugcFromZoneUrl(p.path(field).asText(null));
            if (ugc != null) codes.add(ugc);
        }
        return Set.copyOf(codes);
    }

    /**
     * {@code https://api.weather.gov/zones/forecast/AZZ560} -> {@code AZZ560}.
     * The same trailing segment appears in an alert's {@code affectedZones}
     * URLs, which is why this service carries UGC codes rather than the URLs:
     * they are the same fact, and the code is the half that matches.
     */
    static String ugcFromZoneUrl(String url) {
        if (url == null || url.isBlank()) return null;
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) return null;
        String ugc = url.substring(slash + 1).trim().toUpperCase(Locale.ROOT);
        return ugc.isEmpty() ? null : ugc;
    }

    // ------------------------------------------------------------------
    // Zone -> centroid (the dispatch path)
    // ------------------------------------------------------------------

    /**
     * Representative coordinate for a zone, as {@code {lat, lng}}.
     *
     * <p><b>Cache-only and non-blocking</b> — returns empty on a miss rather
     * than fetching. Dispatch runs on a cron every 5 minutes, so a zone that
     * misses on this tick is warmed by {@link #warmZones} and dispatches on the
     * next one. That is a bounded delay on a background job; a blocking fetch
     * here would put a 200 ms upstream call inside a transaction, once per
     * alert.</p>
     */
    public Optional<double[]> centroidForZone(String ugc) {
        if (ugc == null) return Optional.empty();
        double[] c = zoneCentroids.get(ugc.toUpperCase(Locale.ROOT));
        return c == null ? Optional.empty() : Optional.of(new double[] { c[0], c[1] });
    }

    /**
     * Queue background resolution of any zone codes not yet attempted. Safe to
     * call on every ingest tick — already-attempted codes are skipped, so a
     * steady-state feed queues nothing.
     */
    public void warmZones(Collection<String> ugcs) {
        if (!enabled) return;
        List<String> queued = selectForWarm(ugcs, zoneCentroids.keySet(), centroidAttempted,
                MAX_WARM_PER_TICK);
        if (queued.isEmpty()) return;

        log.info("NwsZone: warming {} zone centroid(s)", queued.size());
        for (String ugc : queued) {
            warmPool.submit(() -> {
                resolveCentroid(ugc);
                try { Thread.sleep(WARM_SPACING_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }
    }

    /**
     * Which codes to warm this tick — the throttle decision, as a pure
     * function so it can be asserted without a network or a thread.
     *
     * <p><b>Extracted deliberately.</b> The 909-fetch burst this bounds
     * (P1-11) was found by reading a log line during a full test run, which is
     * not a control — nothing would stop the next edit reintroducing it
     * silently. The cap is now a testable decision rather than an
     * observation.</p>
     *
     * <p><b>Mutates {@code attempted}</b>: selected codes are marked so a
     * later tick skips them, and codes deferred past the cap are explicitly
     * left unmarked so the next tick still sees them. Getting that backwards
     * is how a truncation becomes a permanent drop.</p>
     *
     * @param ugcs      candidate codes, may contain nulls / blanks / dupes
     * @param known     codes whose centroid is already cached
     * @param attempted codes already tried — mutated, see above
     * @param cap       maximum to queue this tick
     */
    static List<String> selectForWarm(Collection<String> ugcs,
                                      Set<String> known,
                                      Set<String> attempted,
                                      int cap) {
        if (ugcs == null || ugcs.isEmpty() || cap <= 0) return List.of();

        List<String> todo = new ArrayList<>();
        for (String raw : ugcs) {
            if (raw == null || raw.isBlank()) continue;
            String ugc = raw.trim().toUpperCase(Locale.ROOT);
            if (known.contains(ugc)) continue;
            if (!attempted.add(ugc)) continue;   // already tried, don't loop on it
            todo.add(ugc);
            if (todo.size() > cap) {
                // Over the line: un-mark it and stop. Leaving it marked would
                // mean this tick's truncation silently became permanent.
                attempted.remove(ugc);
                todo.remove(todo.size() - 1);
                log.info("NwsZone: warm queue hit the {}/tick cap; remainder deferred", cap);
                break;
            }
        }
        return List.copyOf(todo);
    }

    private void resolveCentroid(String ugc) {
        try {
            String type = zoneTypeFor(ugc);
            if (type == null) return;
            JsonNode root = fetchJson(String.format(ZONE_URL, type, ugc));
            double[] centroid = centroidOf(root.path("geometry"));
            if (centroid == null) return;
            if (zoneCentroids.size() < MAX_CACHE_ENTRIES) {
                zoneCentroids.put(ugc, centroid);
            }
        } catch (Exception e) {
            // A zone we can't resolve simply has no auto-post coordinate. It
            // still matches on the read path, which is the life-safety half.
            //
            // Clear the attempted mark so a transient failure retries on a
            // later tick. Without this a single upstream blip would pin that
            // zone to "no coordinate" for the life of the process, and every
            // alert targeting it would stop dispatching. The per-tick cap is
            // what keeps the retry from becoming a hot loop.
            centroidAttempted.remove(ugc);
            log.debug("NwsZone: centroid resolve failed for {}: {}", ugc, e.getMessage());
        }
    }

    /**
     * UGC third character encodes the zone kind: {@code C} is a county zone,
     * {@code Z} is a forecast/fire zone. The two share the {@code Z} letter and
     * are distinguished only by which endpoint answers, so a {@code Z} code is
     * tried as {@code forecast} — the fire zones we care about
     * ({@code ORZ691}) resolve there too, and a miss just means no centroid.
     */
    static String zoneTypeFor(String ugc) {
        if (ugc == null || ugc.length() < 3) return null;
        char kind = Character.toUpperCase(ugc.charAt(2));
        if (kind == 'C') return "county";
        if (kind == 'Z') return "forecast";
        return null;
    }

    /**
     * Arithmetic mean of every vertex in a GeoJSON Polygon / MultiPolygon.
     *
     * <p>Not the true area centroid, and deliberately so: this places an
     * auto-post and centres a push radius, both of which want "somewhere
     * representative inside the zone", and a vertex mean is stable, cheap, and
     * has no degenerate cases. It is strictly better than the first-vertex
     * coordinate the dispatcher uses today (audit P1-3), which can sit tens of
     * miles from the affected population.</p>
     *
     * @return {@code {lat, lng}}, or null when the geometry is unusable
     */
    static double[] centroidOf(JsonNode geometry) {
        if (geometry == null || geometry.isMissingNode() || geometry.isNull()) return null;
        JsonNode coords = geometry.path("coordinates");
        if (coords.isMissingNode() || coords.isNull()) return null;
        double[] acc = new double[2];
        int[] n = new int[1];
        accumulate(coords, acc, n);
        if (n[0] == 0) return null;
        return new double[] { acc[1] / n[0], acc[0] / n[0] };   // {lat, lng}
    }

    /** Walk arbitrarily-nested coordinate arrays down to [lon, lat] pairs. */
    private static void accumulate(JsonNode node, double[] acc, int[] n) {
        if (!node.isArray() || node.isEmpty()) return;
        JsonNode first = node.get(0);
        if (first.isNumber()) {
            if (node.size() >= 2) {
                acc[0] += node.get(0).asDouble();
                acc[1] += node.get(1).asDouble();
                n[0]++;
            }
            return;
        }
        for (JsonNode child : node) accumulate(child, acc, n);
    }

    // ------------------------------------------------------------------

    private JsonNode fetchJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/geo+json, application/json;q=0.9")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("Upstream " + url + " returned HTTP " + resp.statusCode());
        }
        return json.readTree(resp.body());
    }

    // ------------------------------------------------------------------
    // Test seams
    // ------------------------------------------------------------------

    /** Exposed so the throttle bound is asserted, not just declared. */
    static int maxWarmPerTick() { return MAX_WARM_PER_TICK; }

    /** Exposed so "spacing exists and is non-zero" is a test, not a comment. */
    static long warmSpacingMs() { return WARM_SPACING_MS; }

    /** Seed a point's zone codes without touching the network (tests). */
    void seedPointZones(double lat, double lng, Set<String> codes) {
        pointZones.put(String.format(Locale.ROOT, POINT_KEY_FMT, lat, lng), Set.copyOf(codes));
    }

    /** Seed a zone centroid without touching the network (tests). */
    void seedCentroid(String ugc, double lat, double lng) {
        zoneCentroids.put(ugc.toUpperCase(Locale.ROOT), new double[] { lat, lng });
        centroidAttempted.add(ugc.toUpperCase(Locale.ROOT));
    }

    void setEnabled(boolean value) {
        this.enabled = value;
    }
}
