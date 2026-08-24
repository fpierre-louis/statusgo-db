package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.service.AlertFeedService;
import io.sitprep.sitprepapi.service.AlertIngestService;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-side cached alert feed. Centralizes what the FE was doing
 * per-page (NWS direct-fetch in {@code emergencyApis.js}) so we don't
 * fan out to public-data APIs once per user × per page-load.
 *
 * <p>Sources merged into the snapshot: NWS active alerts, USGS recent
 * quakes (M4.5+), FEMA active disaster declarations. All three are
 * polled by {@link AlertIngestService} on a 5-min fixedDelay; this
 * resource just reads the in-memory cache.</p>
 *
 * <p>READS are unauthenticated — alert data is public and the response
 * carries no user-specific information. The WRITE ({@code POST /refresh})
 * is not: see its own note.</p>
 *
 * <p>Phase 2a (current) supports lat/lng + radiusMi for coarse
 * point-radius filtering. Phase 3 (per docs/ALERTS_INTEGRATION.md)
 * adds geocell-scoped {@code AlertPost} dispatch + STOMP broadcast.</p>
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertResource {

    private final AlertIngestService ingest;
    private final AlertFeedService feedService;
    private final PlatformAccessService platformAccessService;

    public AlertResource(AlertIngestService ingest,
                         AlertFeedService feedService,
                         PlatformAccessService platformAccessService) {
        this.ingest = ingest;
        this.feedService = feedService;
        this.platformAccessService = platformAccessService;
    }

    /**
     * Latest cached snapshot. Returns immediately (no upstream call).
     * The FE can decide based on {@code lastSuccessAt} whether to fall
     * back to a direct NWS call if the data is too stale.
     *
     * <p>Optional filter: if {@code lat} + {@code lng} are both present,
     * the response is filtered server-side to alerts whose geometry's
     * first coordinate falls within {@code radiusMi} (default 250mi) of
     * the point. Alerts without geometry are always included. This is a
     * coarse filter — exact point-in-polygon happens client-side via
     * Leaflet rendering. Without lat/lng, returns the full ~400-alert
     * snapshot (roughly 440KB) which is fine for desktop / dashboards.</p>
     */
    @GetMapping("/active")
    public ResponseEntity<Snapshot> active(
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lng", required = false) Double lng,
            @RequestParam(value = "radiusMi", required = false, defaultValue = "250") double radiusMi
    ) {
        if (lat != null && lng != null) {
            return ResponseEntity.ok(ingest.getSnapshotForPoint(lat, lng, radiusMi));
        }
        return ResponseEntity.ok(ingest.getSnapshot());
    }

    /**
     * Card-shaped alert feed for a coordinate.
     *
     * <p>The surface the hazard redesign consumes. Unlike {@code /active},
     * which returns the normalized wire shape, this returns
     * {@link AlertFeedResponse} — plain-language copy separated from the
     * official text, tier and life-threatening status passed through from the
     * dispatch pipeline, the match reason on every card, and the coverage
     * caveat on every response.</p>
     */
    @GetMapping("/feed")
    public ResponseEntity<AlertFeedResponse> feed(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng
    ) {
        return ResponseEntity.ok(feedService.feedFor(lat, lng));
    }

    /**
     * Manual refresh. Runs the upstream poll synchronously then returns the new
     * snapshot. Useful for QA and during deploys when you want fresh data
     * without waiting for the 5-minute scheduler tick.
     *
     * <p><b>Platform admin only, since 2026-08-24.</b> It was open, reasoned as
     * "the data is public; can tighten to admin-only later if abuse becomes a
     * concern". But what is public here is the <em>response</em>, and that was
     * never the exposure — the exposure is the <em>work</em>. This is a write:
     * {@code refreshNow()} performs a synchronous upstream poll, so anyone on
     * the internet could make our server hammer NWS on demand, in a loop, with
     * no token and no rate limit. The two ways that goes wrong both land on us:
     * an outbound-abuse pattern against a free public service from our IP, and
     * a trivial way to occupy the single web dyno.</p>
     *
     * <p>No frontend caller — the scheduler owns the refresh, and the FE reads
     * {@code /active} and {@code /feed}. Gated with {@code VIEW_METRICS}, the
     * operator-facing permission, and it accepts the break-glass admin token
     * header so it stays usable from a terminal during a deploy, which is what
     * it is actually for.</p>
     */
    @PostMapping("/refresh")
    public ResponseEntity<Snapshot> refresh(
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token)
                .require(PlatformPermission.VIEW_METRICS);
        ingest.refreshNow();
        return ResponseEntity.ok(ingest.getSnapshot());
    }
}
