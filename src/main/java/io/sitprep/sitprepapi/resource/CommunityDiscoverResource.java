package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.CommunityDiscoverDto;
import io.sitprep.sitprepapi.service.CommunityDiscoverService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Community discover surface. Mirrors the data shape that the
 * {@code community/CommunityDiscoverPage} renders so the page is
 * effectively a thin view over the response.
 */
@RestController
@RequestMapping("/api/community")
public class CommunityDiscoverResource {

    private final CommunityDiscoverService service;

    public CommunityDiscoverResource(CommunityDiscoverService service) {
        this.service = service;
    }

    /**
     * GET /api/community/discover?lat=33.749&lng=-84.388&radiusKm=10
     *                            &viewerEmail=alice@example.com&includeMine=false
     *
     * Returns the reverse-geocoded place label for the viewer plus public
     * groups within {@code radiusKm}. Default radius: 10 km.
     *
     * <p>{@code viewerEmail} is optional — when provided, the viewer's
     * existing memberships are filtered out so the page can show "groups
     * you could join" without client-side bookkeeping. Falls back to the
     * security context's verified email when the param is absent.</p>
     *
     * <p>{@code includeMine=true} keeps the viewer's existing groups in
     * the response (each tagged with {@code viewerIsMember=true}) for
     * surfaces that want to show both lists.</p>
     */
    @GetMapping("/discover")
    public ResponseEntity<CommunityDiscoverDto> discover(
            @RequestParam("lat") Double lat,
            @RequestParam("lng") Double lng,
            @RequestParam(value = "radiusKm", required = false, defaultValue = "10") double radiusKm,
            @RequestParam(value = "includeMine", required = false, defaultValue = "false") boolean includeMine
    ) {
        // Auth optional (2026-06-02) — guest sessions land on /community
        // and need this endpoint to surface place label + nearby public
        // groups; previously this required auth and 401'd, surfacing a
        // "Couldn't load nearby groups" red toast for every guest user.
        // The response shape is public-equivalent (no private group data,
        // no per-user membership state when viewer is null); the service
        // already handles null viewer correctly (treats as
        // not-a-member-of-anything). getCurrentUserEmail returns null
        // when no Firebase token is present; requireAuthenticatedEmail
        // would throw — we want the former here.
        String viewer = AuthUtils.getCurrentUserEmail();
        return ResponseEntity.ok(service.discover(lat, lng, radiusKm, viewer, includeMine));
    }
}
