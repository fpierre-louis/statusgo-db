package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.VerifiedPublisherDto;
import io.sitprep.sitprepapi.service.VerifiedPublisherService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Verified publisher endpoints — discovery (public, unauthenticated)
 * + admin verification (token-gated). Spec:
 * {@code docs/SPONSORED_AND_ALERT_MODE.md} "Endpoints needed".
 *
 * <pre>
 *   GET   /api/verified-publishers?lat=&amp;lng=&amp;radiusMi=
 *   PATCH /api/admin/users/{email}/verify-publisher
 *         body: { verified: bool, kind: string }
 *         header: X-Sitprep-Admin-Token
 * </pre>
 *
 * <p>Admin auth is a shared-secret token in {@code APP_ADMIN_TOKEN}
 * env var. Spec calls admin tooling "CLI-driven initially" — a real
 * RBAC layer comes when the moderator dashboard ships. Until then a
 * curl call from an admin's machine is the only way in. The token is
 * compared in constant time to avoid timing oracles.</p>
 */
@RestController
public class VerifiedPublisherResource {

    private final VerifiedPublisherService service;
    private final String adminToken;

    public VerifiedPublisherResource(VerifiedPublisherService service,
                                     @Value("${app.admin.token:}") String adminToken) {
        this.service = service;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @GetMapping("/api/verified-publishers")
    public ResponseEntity<List<VerifiedPublisherDto>> discover(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam(value = "radiusMi", required = false, defaultValue = "50") double radiusMi
    ) {
        double radiusKm = radiusMi * 1.609344;
        return ResponseEntity.ok(service.discoverInRadius(lat, lng, radiusKm));
    }

    @PatchMapping("/api/admin/users/{email}/verify-publisher")
    public ResponseEntity<VerifiedPublisherDto> setVerified(
            @PathVariable String email,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        requireAdmin(token);
        boolean verified = Boolean.TRUE.equals(body.get("verified"));
        String kind = body.get("kind") instanceof String s ? s : null;
        // adminEmail is logged from the token caller; for v1 we don't
        // resolve it to a UserInfo, since the token is just a shared
        // secret. Future: real admin identities + audit-log row.
        VerifiedPublisherDto out = service.setVerified(email, verified, kind, "admin-token");
        return ResponseEntity.ok(out);
    }

    private void requireAdmin(String token) {
        if (adminToken.isEmpty()) {
            // No admin token configured → endpoint is disabled. Fail
            // closed rather than open a gap.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Admin endpoints disabled (APP_ADMIN_TOKEN not set)");
        }
        if (token == null || !constantTimeEquals(token, adminToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Admin token required");
        }
    }

    /** Constant-time string compare to avoid timing oracles on token check. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
