package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.AlertIngestService;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
     */
    @GetMapping("/active")
    public ResponseEntity<Snapshot> active() {
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
