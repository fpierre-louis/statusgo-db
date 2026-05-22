package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.GeocodeService;
import io.sitprep.sitprepapi.service.GeocodeService.Suggestion;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Free address geocoding (OpenStreetMap Nominatim) — see
 * {@link GeocodeService}. Backs the FE address type-ahead + "use my
 * current location" autofill so no Google Places billing is incurred.
 *
 * <p>Reads are unauthenticated (public data), consistent with the other
 * public lookups ({@code /api/alerts/active}, {@code /api/shelters/search}).</p>
 *
 * <pre>
 *   GET /api/geocode/search?q=4053%20Rivermist&limit=5    type-ahead
 *   GET /api/geocode/reverse?lat=40.43&lng=-111.88        coords → address
 * </pre>
 */
@RestController
@RequestMapping("/api/geocode")
public class GeocodeResource {

    private final GeocodeService geocode;

    public GeocodeResource(GeocodeService geocode) {
        this.geocode = geocode;
    }

    @GetMapping("/search")
    public ResponseEntity<List<Suggestion>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(geocode.forwardSearch(q, limit));
    }

    @GetMapping("/reverse")
    public ResponseEntity<Suggestion> reverse(
            @RequestParam("lat") Double lat,
            @RequestParam("lng") Double lng
    ) {
        Suggestion s = geocode.reverse(lat, lng);
        return s == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(s);
    }
}
