package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.AlertIngestService;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-side cached alert feed. Centralizes what the FE was doing
 * per-page (NWS direct-fetch in {@code emergencyApis.js}) so we don't
 * fan out to NWS once per user × per page-load.
 *
 * <p>Reads are unauthenticated — alert data is public (NWS is a public
 * feed). The endpoint can stay open even if we tighten elsewhere because
 * the response carries no user-specific information.</p>
 *
 * <p>Phase 1 returns the full snapshot; the FE filters by location.
 * Phase 2 (per docs/ALERTS_INTEGRATION.md) adds geocell-scoped filtering
 * + AlertPost dispatch + STOMP broadcast.</p>
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertResource {

    private final AlertIngestService ingest;

    public AlertResource(AlertIngestService ingest) {
        this.ingest = ingest;
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
     * Manual refresh. Runs the upstream poll synchronously then returns
     * the new snapshot. Useful for QA + during deploys when you want to
     * see fresh data without waiting for the 5-minute scheduler tick.
     * Open for now since the data is public; can tighten to admin-only
     * later if abuse becomes a concern.
     */
    @PostMapping("/refresh")
    public ResponseEntity<Snapshot> refresh() {
        ingest.refreshNow();
        return ResponseEntity.ok(ingest.getSnapshot());
    }
}
