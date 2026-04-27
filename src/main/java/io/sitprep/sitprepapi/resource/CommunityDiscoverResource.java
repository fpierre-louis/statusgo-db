package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.CommunityDiscoverDto;
import io.sitprep.sitprepapi.service.CommunityDiscoverService;
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
     *
     * Returns the reverse-geocoded place label for the viewer plus public
     * groups within {@code radiusKm}. Default radius: 10 km.
     */
    @GetMapping("/discover")
    public ResponseEntity<CommunityDiscoverDto> discover(
            @RequestParam("lat") Double lat,
            @RequestParam("lng") Double lng,
            @RequestParam(value = "radiusKm", required = false, defaultValue = "10") double radiusKm
    ) {
        return ResponseEntity.ok(service.discover(lat, lng, radiusKm));
    }
}
