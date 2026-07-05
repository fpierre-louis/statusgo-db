package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.ExternalPoiCache;
import io.sitprep.sitprepapi.dto.MapPoiDto;
import io.sitprep.sitprepapi.repo.ExternalPoiCacheRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * L2 tile cache + stale-while-revalidate for external POIs — Community map
 * Phase 2 (docs/COMMUNITY_API_GAMEPLAN.md §3.3).
 *
 * <p>Read path (never blocks on Overpass):</p>
 * <ul>
 *   <li><b>Fresh hit</b> → return cached MapPoi[] immediately.</li>
 *   <li><b>Stale hit</b> (expired) → return the stale payload immediately AND
 *       kick an async refresh.</li>
 *   <li><b>Cold miss</b> → return empty AND kick an async refresh (POIs appear
 *       on the next viewport settle once the tile is warm).</li>
 * </ul>
 *
 * <p>Requests coalesce onto a <b>tile-snapped bounding box</b> (a fixed
 * lat/lng grid), so adjacent pans share a cache row. A single-flight guard
 * stops concurrent misses for the same tile from stampeding Overpass. The
 * refresh runs on a tiny bounded pool (memory-frugal for the 512 MB dyno);
 * an L1 in-process cache is intentionally deferred (DB L2 hit is already well
 * under the latency budget).</p>
 */
@Service
public class ExternalPoiCacheService {

    private static final Logger log = LoggerFactory.getLogger(ExternalPoiCacheService.class);

    // ~0.05° grid (~5.5 km) — one cell ≈ a z13 tile. Snapping OUTWARD (floor min
    // / ceil max) guarantees the tile fully contains any z>=13 viewport, so
    // there is never an edge-miss; adjacent viewports in the same cell share a
    // key. A viewport straddling cells snaps to a slightly larger box.
    private static final double GRID = 0.05;
    // Safety cap: never Overpass-query an absurdly large box (band gating keeps
    // us at z>=13 so this rarely trips).
    private static final double MAX_SPAN_DEG = 0.30;
    private static final Duration TTL = Duration.ofHours(24);

    private final ExternalPoiCacheRepo repo;
    private final OverpassService overpass;
    private final ObjectMapper mapper;

    // Single-flight: keys currently being refreshed.
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    // Tiny bounded refresh pool — drop (don't queue unboundedly) under pressure.
    private final ExecutorService refreshPool = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(32),
            r -> {
                Thread t = new Thread(r, "overpass-refresh");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    public ExternalPoiCacheService(ExternalPoiCacheRepo repo, OverpassService overpass, ObjectMapper mapper) {
        this.repo = repo;
        this.overpass = overpass;
        this.mapper = mapper;
    }

    /**
     * External POIs covering the given viewport, served from cache with SWR.
     * The returned list is for the snapped tile (⊇ viewport); the caller
     * filters to the exact viewport + assigns distance.
     */
    public List<MapPoiDto> getPois(double minLat, double minLng, double maxLat, double maxLng) {
        double s = floorGrid(minLat), w = floorGrid(minLng);
        double n = ceilGrid(maxLat), e = ceilGrid(maxLng);
        if ((n - s) > MAX_SPAN_DEG || (e - w) > MAX_SPAN_DEG) {
            return List.of(); // too zoomed out for POIs
        }
        String key = "overpass:" + fmt(s) + ":" + fmt(w) + ":" + fmt(n) + ":" + fmt(e);

        Optional<ExternalPoiCache> hit = repo.findById(key);
        if (hit.isPresent()) {
            ExternalPoiCache row = hit.get();
            if (row.getExpiresAt().isAfter(Instant.now())) {
                return deserialize(row.getPayload());      // fresh
            }
            triggerRefresh(key, s, w, n, e);               // stale → refresh async
            return deserialize(row.getPayload());          // ...but serve stale now
        }

        triggerRefresh(key, s, w, n, e);                   // cold → warm it, serve empty
        return List.of();
    }

    // ── SWR internals ───────────────────────────────────────────────────────

    private void triggerRefresh(String key, double s, double w, double n, double e) {
        if (!inFlight.add(key)) return; // a refresh for this tile is already running
        try {
            refreshPool.submit(() -> {
                try {
                    List<MapPoiDto> pois = overpass.fetch(s, w, n, e);
                    // Cache even an empty result (negative cache) so we don't
                    // hammer Overpass for genuinely empty tiles.
                    String json = mapper.writeValueAsString(pois);
                    Instant now = Instant.now();
                    repo.save(new ExternalPoiCache(key, "overpass", json, now, now.plus(TTL)));
                } catch (Exception ex) {
                    log.warn("Overpass cache refresh failed for {}: {}", key, ex.toString());
                } finally {
                    inFlight.remove(key);
                }
            });
        } catch (RuntimeException rejected) {
            inFlight.remove(key); // pool saturated (DiscardPolicy) — try again next pan
        }
    }

    private List<MapPoiDto> deserialize(String payload) {
        try {
            MapPoiDto[] arr = mapper.readValue(payload, MapPoiDto[].class);
            return List.of(arr);
        } catch (Exception e) {
            log.warn("Corrupt external-POI cache payload: {}", e.toString());
            return List.of();
        }
    }

    private static double floorGrid(double v) { return Math.floor(v / GRID) * GRID; }
    private static double ceilGrid(double v) { return Math.ceil(v / GRID) * GRID; }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
}
