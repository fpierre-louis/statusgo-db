package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
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
 *   <li>{@code GET  /api/resources/{id}/preview} — public-safe read shape
 *       for a shared resource link that may be outside the viewer's board
 *       radius.</li>
 *   <li>{@code POST /api/resources} — a resident submits a resource.</li>
 * </ul>
 *
 * <p>The board and submit routes require a verified token, consistent with
 * the rest of the post / group surfaces. The preview route mirrors
 * {@code /share/resource/{id}} unfurls: approved listings only, no submitter
 * email, and safe for logged-out share landing.</p>
 */
@RestController
@RequestMapping("/api/resources")
@CrossOrigin(origins = "http://localhost:3000")
public class ResourceListingResource {

    private final ResourceListingService service;

    public ResourceListingResource(ResourceListingService service) {
        this.service = service;
    }

    // Wrapped in {@link ApiResponse} per P2-3 (audit BE-02 / BE-15). FE
    // axios interceptor unwraps response.data to the inner list so legacy
    // callers see the same shape they always did.
    @GetMapping
    public ResponseEntity<ApiResponse<List<ResourceListingDto>>> board(
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lng", required = false) Double lng,
            @RequestParam(value = "radiusKm", required = false) Double radiusKm) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(service.board(lat, lng, radiusKm), ApiMeta.now()));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<ApiResponse<ResourceListingDto>> preview(@PathVariable Long id) {
        return service.findPublicPreview(id)
                .map(dto -> ResponseEntity.ok(ApiResponse.ok(dto, ApiMeta.now())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ResourceListingDto>> submit(@RequestBody SubmitResourceRequest req) {
        String email = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(service.submit(req, email), ApiMeta.now()));
    }
}
