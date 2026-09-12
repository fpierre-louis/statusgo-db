package io.sitprep.sitprepapi.resource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Can the client reach SitPrep?" — and nothing else.
 *
 * <p>── WHY THIS EXISTS ─────────────────────────────────────────────────────
 *
 * <p>The frontend used {@code navigator.onLine} as a proxy for whether SitPrep
 * was usable. That reports whether the OS believes a network INTERFACE exists,
 * which is a different question and is {@code true} in every case that matters:
 * a severed fibre trunk upstream of a working router, a captive portal, DNS
 * failure, a backend that is down. Stress-test Scenario 22 is built on exactly
 * that state — full Wi-Fi, full bars, nothing reachable — and the app showed
 * stale content with no label, looking broken rather than offline.
 *
 * <p>Answering the real question needs a request that actually leaves the
 * device and reaches this service.
 *
 * <p>── WHY A DEDICATED ENDPOINT ───────────────────────────────────────────
 *
 * <p>Every plausible existing candidate was measured against production first,
 * and none of them has a contract worth depending on:
 *
 * <ul>
 *   <li>{@code /actuator/health} — 500. Actuator is not on the classpath;
 *       {@code SecurityConfig} permits the path to a handler that does not
 *       exist.</li>
 *   <li>{@code /api/config/defaults} — 401 when signed out, so it cannot be
 *       probed before login, and its 200 shape is a real payload we would be
 *       fetching purely to throw away.</li>
 *   <li>Any nonexistent {@code /api/*} path — 401, because {@code /api/**} is
 *       {@code authenticated()} and Security answers before routing. It "works"
 *       as a probe by accident, which is the problem: nothing stops a later
 *       change from making that 401 mean something else, and the probe would go
 *       on passing while meaning nothing.</li>
 * </ul>
 *
 * <p>So this is a narrow endpoint whose entire contract is its status code.
 *
 * <p>── THE CONTRACT ───────────────────────────────────────────────────────
 *
 * <ul>
 *   <li><b>204 No Content.</b> No body, nothing to parse, nothing to leak.</li>
 *   <li><b>Unauthenticated</b> — lives under {@code /api/public/**}, which
 *       {@code SecurityConfig} pins to {@code permitAll}. Reachability has to be
 *       answerable while signed out and while a token is expired, since those
 *       are precisely the states a user is in when they need to know whether the
 *       app can talk to anything.</li>
 *   <li><b>No database, no snapshot, no I/O.</b> It is safe to call on resume
 *       and after a failed request without adding load. It deliberately does
 *       NOT report health: a 204 means "your packets reached SitPrep", not "the
 *       service is well". Conflating the two would make the banner lie in the
 *       other direction.</li>
 *   <li><b>HEAD is supported</b> for callers that want to skip the response
 *       entirely.</li>
 * </ul>
 *
 * <p><b>The client treats ANY HTTP response as reachable</b> — 204, 401, 500,
 * anything. Only a transport failure or a timeout counts as unreachable. That
 * is the point: a 500 from this service still proves the service answered.
 */
@RestController
@RequestMapping("/api/public")
public class ReachabilityResource {

    /**
     * 204, always. See the class note: the status code IS the contract, and the
     * client must not require 204 specifically to consider SitPrep reachable.
     */
    @RequestMapping(value = "/ping", method = { RequestMethod.GET, RequestMethod.HEAD })
    public ResponseEntity<Void> ping() {
        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                // Never let a proxy or the browser answer this from cache — a
                // cached 204 would report a service that is not there.
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .build();
    }
}
