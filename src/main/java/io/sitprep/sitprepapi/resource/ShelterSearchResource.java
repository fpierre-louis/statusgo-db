package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.ShelterSearchService;
import io.sitprep.sitprepapi.service.ShelterSearchService.Shelter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Free emergency-shelter search backed by OpenStreetMap (Nominatim +
 * Overpass) — see {@link ShelterSearchService}. Replaces the FE's prior
 * Google Places text-search so the shelter step carries no Google
 * billing.
 *
 * <p>Reads are unauthenticated (public data, no user-specific content) —
 * consistent with {@code /api/alerts/active}.</p>
 *
 * <pre>
 *   GET /api/shelters/search?lat=40.43&lng=-111.88        (use my location)
 *   GET /api/shelters/search?q=Lehi%2C%20UT               (city / zip)
 *   GET /api/shelters/search?q=84043&radiusMi=25
 * </pre>
 */
@RestController
@RequestMapping("/api/shelters")
public class ShelterSearchResource {

    private final ShelterSearchService shelterSearch;

    public ShelterSearchResource(ShelterSearchService shelterSearch) {
        this.shelterSearch = shelterSearch;
    }

    @GetMapping("/search")
    public ResponseEntity<List<Shelter>> search(
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lng", required = false) Double lng,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "radiusMi", required = false) Double radiusMi,
            @RequestParam(value = "petFriendly", required = false, defaultValue = "false") boolean petFriendly,
            @RequestParam(value = "adaAccessible", required = false, defaultValue = "false") boolean adaAccessible
    ) {
        return ResponseEntity.ok(shelterSearch.search(lat, lng, q, radiusMi, petFriendly, adaAccessible));
    }

    /**
     * Currently-OPEN disaster shelters from FEMA's National Shelter System
     * (gis.fema.gov). Complements {@code /search} (OSM permanent shelters) —
     * this is the disaster-activated feed, near-empty in calm times. Server-side
     * proxy + cache for the FE's retired direct OpenFEMA OpenShelters call.
     *
     * <pre>
     *   GET /api/shelters/open?lat=40.43&lng=-111.88&radiusMi=100[&state=UT]
     * </pre>
     */
    @GetMapping("/open")
    public ResponseEntity<List<Shelter>> open(
            @RequestParam("lat") Double lat,
            @RequestParam("lng") Double lng,
            @RequestParam(value = "radiusMi", required = false) Double radiusMi,
            @RequestParam(value = "state", required = false) String state
    ) {
        return ResponseEntity.ok(shelterSearch.openDisasterShelters(lat, lng, radiusMi, state));
    }
}
