package io.sitprep.sitprepapi.resource;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.sitprep.sitprepapi.constant.FeedRadius;

import java.util.Map;

/**
 * App-level configuration defaults served to the frontend so the FE
 * doesn't have to hard-code constants that we want to be able to tune
 * centrally (e.g. radius ladders for location-aware surfaces).
 *
 * <p>This endpoint is intentionally tiny. The FE caches the response and
 * refreshes on long intervals (24h) so a server-side change rolls out without a
 * full app reload.</p>
 *
 * <p><b>It is NOT authentication-free, and this javadoc used to say it was.</b>
 * {@code /api/config/**} is deliberately absent from the SecurityConfig
 * allowlist (audit {@code docs/audits/2026-08-24-public-route-allowlist.md}),
 * so an anonymous request gets 401 — measured against prod v553. That is a
 * ruling, not an oversight, and {@code SecurityConfigAllowlistTest} asserts it
 * so a future reader cannot quietly flip it. It costs nothing because
 * {@code useAppDefaults} falls back to bundled constants that match these
 * values; a signed-out client simply never sees a server retune.</p>
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
     *   <li><b>max</b> — the hard ceiling the server clamps every feed query
     *       to ({@link FeedRadius}). Not a default and not selectable; the
     *       widest rung's copy states it.</li>
     * </ul>
     */
    private static final Map<String, Integer> RADIUS_MI = Map.of(
            "default", 50,
            "community", 50,
            "alerts", 50,
            "marketplace", 50,
            // The server's HARD CEILING, not a default — no request reaches
            // further than this whatever it asks for. It is here because the
            // frontend was deriving it: `radiusOverride.js` carried
            // `MAX_RADIUS_MI = 310` with the 500/1.609344 conversion done by
            // hand in a comment, and CLAUDE.md lists that as a standing example
            // of a server constant duplicated across a deploy boundary. Shipping
            // it costs one map entry and retires the duplicate.
            "max", FeedRadius.maxMiles()
    );

    /**
     * GET /api/config/defaults
     *
     * <p>Returns the tunable app defaults. Response shape:</p>
     * <pre>
     * {
     *   "radiusMi": { "default": 50, "community": 50, "alerts": 50, "marketplace": 50, "max": 310 }
     * }
     * </pre>
     *
     * <p>Cache for ~24h on the client side. <b>Requires auth</b> — see the class
     * javadoc. The previous claim here, that it "must succeed even before
     * sign-in so the welcome flow can render correctly", was false in both
     * halves: it does not succeed unauthenticated, and the welcome flow renders
     * anyway on the client's bundled fallback.</p>
     */
    /**
     * The alerts radius, for server-side consumers.
     *
     * <p>Exists so {@code AlertCardDto.radiusMi} reads this value rather than
     * carrying a copy of it (audit P1-7). Three layers already disagreed by
     * 50x — 250 mi in the API default, 50 here, 5 hardcoded on two frontend
     * surfaces — and a hand-copied literal in the DTO would have made four.</p>
     */
    public static int alertsRadiusMi() {
        return RADIUS_MI.get("alerts");
    }

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
