package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ResourceListingDto;
import io.sitprep.sitprepapi.dto.SubmitResourceRequest;
import io.sitprep.sitprepapi.service.ResourceListingService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Community resource board — see {@link io.sitprep.sitprepapi.domain.ResourceListing}.
 *
 * <ul>
 *   <li>{@code GET  /api/resources?lat&lng&radiusKm} — board for a
 *       viewer. National listings always; geo-pinned ones within the
 *       backend default radius (or {@code radiusKm} when supplied).</li>
 *   <li>{@code POST /api/resources} — a resident submits a resource.</li>
 * </ul>
 *
 * <p>Both require a verified token, consistent with the rest of the
 * post / group surfaces.</p>
 */
@RestController
@RequestMapping("/api/resources")
@CrossOrigin(origins = "http://localhost:3000")
public class ResourceListingResource {

    private final ResourceListingService service;

    public ResourceListingResource(ResourceListingService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ResourceListingDto>> board(
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lng", required = false) Double lng,
            @RequestParam(value = "radiusKm", required = false) Double radiusKm) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.board(lat, lng, radiusKm));
    }

    @PostMapping
    public ResponseEntity<ResourceListingDto> submit(@RequestBody SubmitResourceRequest req) {
        String email = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.submit(req, email));
    }
}
