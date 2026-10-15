package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.dto.AlertHistoryResponse;
import io.sitprep.sitprepapi.dto.AlertRecordResponse;
import io.sitprep.sitprepapi.service.AlertFeedService;
import io.sitprep.sitprepapi.service.AlertHistoryService;
import io.sitprep.sitprepapi.service.AlertIngestService;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.service.AlertRecordService;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.format.DateTimeParseException;
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
 * is not: see its own note. Nor is the canonical record lookup
 * ({@code GET /record}, owner ruling R16): it is a deep-link resolver for
 * signed-in surfaces (inbox, shared links), not a public alert API, and it is
 * deliberately absent from the SecurityConfig allowlist.</p>
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
    private final AlertHistoryService historyService;
    private final AlertRecordService recordService;
    private final PlatformAccessService platformAccessService;

    public AlertResource(AlertIngestService ingest,
                         AlertFeedService feedService,
                         AlertHistoryService historyService,
                         AlertRecordService recordService,
                         PlatformAccessService platformAccessService) {
        this.ingest = ingest;
        this.feedService = feedService;
        this.historyService = historyService;
        this.recordService = recordService;
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
     *
     * <p>{@code context} (S2 C2) says whose coordinate this is: {@code current}
     * — the default — for the device's own location, {@code home} for the
     * household's (Home sends it with household coordinates). It sets each
     * card's {@code viewerContext.role} and is echoed in {@code meta.context}.
     * Forgiving like {@code fixedAt}: an unknown value is {@code current}, not
     * a 400.</p>
     */
    @GetMapping("/feed")
    public ResponseEntity<AlertFeedResponse> feed(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam(value = "fixedAt", required = false) String fixedAt,
            @RequestParam(value = "context", required = false) String context
    ) {
        return ResponseEntity.ok(feedService.feedForViewer(lat, lng, parseFixedAt(fixedAt), context));
    }

    /**
     * When the client's coordinate was captured, or null.
     *
     * <p>Optional and forgiving on purpose. A malformed timestamp resolves to
     * "unknown", which the response reports as such — it does not 400. The
     * coordinate is still a real coordinate and the alerts for it are still
     * worth returning; refusing the whole request over an unparseable freshness
     * hint would trade a caveat for a blank hazards page.</p>
     */
    private static Instant parseFixedAt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * What was active near a coordinate over a past window.
     *
     * <p><b>Why this exists as a recording rather than a query.</b> Measured
     * 2026-09-07, the NWS archive retains roughly five days: Oklahoma City
     * returns the same 23 alerts whether asked since 2026-09-01 or since
     * 2025-01-01. {@code start} is accepted and then ignored past retention, so
     * a month of history cannot be fetched from upstream at all. It is served
     * here from {@code alert_history}, which {@code AlertHistoryService} writes
     * from the ingest snapshot the poller was already holding and discarding.</p>
     *
     * <p>{@code days} is clamped to the configured retention; the response
     * {@code meta} reports the window actually applied and when recording
     * began, because "nothing happened here" and "we started watching on
     * Tuesday" are different answers and the surface has to be able to tell
     * them apart.</p>
     */
    @GetMapping("/history")
    public ResponseEntity<AlertHistoryResponse> history(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam(value = "days", required = false, defaultValue = "30") int days,
            @RequestParam(value = "fixedAt", required = false) String fixedAt
    ) {
        return ResponseEntity.ok(
                historyService.historyFor(lat, lng, days, parseFixedAt(fixedAt)));
    }

    /**
     * The canonical record of one alert (S2 contract §4) — what every link to an
     * alert resolves through, whatever spelling it carries and whatever has
     * happened to the alert since.
     *
     * <p>{@code key} is {@code SOURCE:id}, the legacy {@code SOURCE-id}, or a
     * bare upstream id with {@code source}. A bare id with no {@code source}
     * is answered {@code status: "ambiguous"}, never guessed. Every outcome is a
     * 200 with a {@code status} (live | record | ambiguous | not_found): a
     * stale link is a normal thing to follow, not an error.</p>
     *
     * <p>{@code lat}/{@code lng} are optional — the reader's point, for
     * {@code viewerContext}; an alert that does not apply there still resolves
     * and says so. {@code context} and {@code fixedAt} are forgiving, as on
     * {@code /feed}.</p>
     *
     * <p><b>Authenticated</b> (R16): not on the allowlist, so an anonymous call
     * is a 401 before it reaches here. {@code SecurityConfigAllowlistTest}
     * holds that line.</p>
     */
    @GetMapping("/record")
    public ResponseEntity<AlertRecordResponse> record(
            @RequestParam("key") String key,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lng", required = false) Double lng,
            @RequestParam(value = "context", required = false) String context,
            @RequestParam(value = "fixedAt", required = false) String fixedAt
    ) {
        return ResponseEntity.ok(recordService.lookup(key, source, lat, lng, context, parseFixedAt(fixedAt)));
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
