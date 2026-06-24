package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.dto.VerifiedPublisherDto;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.service.VerifiedPublisherService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import io.sitprep.sitprepapi.util.AuthUtils;

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
    private final PlatformAccessService platformAccessService;

    public VerifiedPublisherResource(VerifiedPublisherService service,
                                     PlatformAccessService platformAccessService) {
        this.service = service;
        this.platformAccessService = platformAccessService;
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

    /**
     * Single-publisher fetch by email — backs the per-business profile
     * page. 404 when the email doesn't resolve to a verified publisher
     * (non-verified users return 404 too — we don't expose verification
     * status via this surface).
     */
    @GetMapping("/api/verified-publishers/{email}")
    public ResponseEntity<VerifiedPublisherDto> getByEmail(@PathVariable String email) {
        return service.findByEmail(email)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Verified publisher not found"));
    }

    @PatchMapping("/api/admin/users/{email}/verify-publisher")
    public ResponseEntity<VerifiedPublisherDto> setVerified(
            @PathVariable String email,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        var access = platformAccessService.resolveForRequest(AuthUtils.getCurrentUserEmail(), token);
        access.require(PlatformPermission.MANAGE_PUBLISHERS);
        boolean verified = Boolean.TRUE.equals(body.get("verified"));
        String kind = body.get("kind") instanceof String s ? s : null;
        VerifiedPublisherDto out = service.setVerified(email, verified, kind, access.auditActorEmail());
        return ResponseEntity.ok(out);
    }
}
