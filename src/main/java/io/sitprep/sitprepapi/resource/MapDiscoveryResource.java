package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.dto.MapDiscoveryDto;
import io.sitprep.sitprepapi.service.MapDiscoveryService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Viewport-driven Community Discovery Engine
 * (docs/COMMUNITY_API_GAMEPLAN.md, Phase 1).
 *
 * <p>The frontend passes the visible map rectangle + zoom; the service returns
 * a normalized, capped {@link MapPoiDto} list (agencies + joinable groups +
 * mutual-aid Posts today; external POIs in Phase 2). Auth is OPTIONAL — a guest
 * panning the community map gets public data; a signed-in viewer additionally
 * gets role-aware {@code viewerRole} on group pins (for the Join CTA).</p>
 */
@RestController
@RequestMapping("/api/community")
public class MapDiscoveryResource {

    private final MapDiscoveryService service;

    public MapDiscoveryResource(MapDiscoveryService service) {
        this.service = service;
    }

    /**
     * GET /api/community/map
     *   ?minLat=..&minLng=..&maxLat=..&maxLng=..&zoom=13
     *
     * Bounding box from Leaflet {@code map.getBounds()}; {@code zoom} drives the
     * progressive-disclosure band (which layers load + the result cap).
     */
    @GetMapping("/map")
    public ResponseEntity<ApiResponse<MapDiscoveryDto>> map(
            @RequestParam("minLat") double minLat,
            @RequestParam("minLng") double minLng,
            @RequestParam("maxLat") double maxLat,
            @RequestParam("maxLng") double maxLng,
            @RequestParam(value = "zoom", required = false, defaultValue = "13") int zoom
    ) {
        String viewer = AuthUtils.getCurrentUserEmail();
        return ResponseEntity.ok(ApiResponse.ok(
                service.discover(minLat, minLng, maxLat, maxLng, zoom, viewer),
                ApiMeta.now()));
    }
}
