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
        // Auth required — the FE only renders this surface for signed-in
        // users (route is ProtectedRoute). Verified token email is the
        // source of viewer identity; the legacy viewerEmail body param is
        // gone now that Phase E is enforced here.
        String viewer = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.discover(lat, lng, radiusKm, viewer, includeMine));
    }
}
