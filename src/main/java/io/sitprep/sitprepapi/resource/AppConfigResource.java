package io.sitprep.sitprepapi.resource;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * App-level configuration defaults served to the frontend so the FE
 * doesn't have to hard-code constants that we want to be able to tune
 * centrally (e.g. radius ladders for location-aware surfaces).
 *
 * <p>This endpoint is intentionally tiny and authentication-free — the
 * values are not user-specific. The FE caches the response and refreshes
 * on long intervals (24h) so a server-side change rolls out without a
 * full app reload.</p>
 *
 * <p><b>Policy (2026-05-12)</b>: only the Community feed allows the
 * user to change radius interactively. Every other location-aware
 * surface (FEMA / NWS alerts, marketplace publisher discovery, map
 * cluster radius, etc.) reads {@code radiusMi.default} from here so
 * the BE remains the single source of truth.</p>
 */
@RestController
@RequestMapping("/api/config")
public class AppConfigResource {

    /**
     * Canonical search-area defaults (miles). Values returned to the FE
     * via the public GET /api/config/defaults endpoint. Keep this map
     * stable in shape — the FE de-references specific keys.
     *
     * <p>Keys:</p>
     * <ul>
     *   <li><b>default</b> — every non-Community surface uses this
     *       (alerts, marketplace, verified-publisher discovery, etc.).</li>
     *   <li><b>community</b> — initial radius the Community feed
     *       opens with. Users can change it via the LocationSheet
     *       chip ladder.</li>
     *   <li><b>alerts</b> — FEMA / NWS / USGS local filter.</li>
     *   <li><b>marketplace</b> — verified-publisher discovery + post
     *       discovery on the marketplace surface.</li>
     * </ul>
     */
    private static final Map<String, Integer> RADIUS_MI = Map.of(
            "default", 50,
            "community", 50,
            "alerts", 50,
            "marketplace", 50
    );

    /**
     * GET /api/config/defaults
     *
     * <p>Returns the tunable app defaults. Response shape:</p>
     * <pre>
     * {
     *   "radiusMi": { "default": 50, "community": 50, "alerts": 50, "marketplace": 50 }
     * }
     * </pre>
     *
     * <p>Cache for ~24h on the client side. No auth — the values are
     * not user-specific and the endpoint must succeed even before
     * sign-in so the welcome flow can render correctly.</p>
     */
    @GetMapping("/defaults")
    public ResponseEntity<Map<String, Object>> getDefaults() {
        Map<String, Object> body = Map.of(
                "radiusMi", RADIUS_MI
        );
        // 1h Cache-Control is a reasonable middle ground — long enough
        // that a session doesn't re-hit, short enough that a server-side
        // change propagates within an hour for already-warm clients.
        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=3600")
                .body(body);
    }
}
