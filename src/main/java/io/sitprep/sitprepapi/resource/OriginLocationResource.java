package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.service.OriginLocationService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/origin-locations")
@CrossOrigin(origins = "http://localhost:3000")
public class OriginLocationResource {

    private final OriginLocationService originService;

    public OriginLocationResource(OriginLocationService originService) {
        this.originService = originService;
    }

    /**
     * GET /api/origin-locations
     * Returns the verified caller's origin locations. ownerEmail param ignored.
     */
    @GetMapping
    public ResponseEntity<List<OriginLocation>> getByOwner(
            @RequestParam(value = "ownerEmail", required = false) String ownerEmailIgnored) {
        String ownerEmail = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(originService.getByOwnerEmail(ownerEmail));
    }

    /**
     * POST /api/origin-locations/bulk
     * Replaces all existing origins for the verified caller.
     * Body's ownerEmail (if present) is ignored.
     */
    @PostMapping("/bulk")
    public ResponseEntity<List<OriginLocation>> saveAllOrigins(@RequestBody Map<String, Object> requestData) {
        String ownerEmail = AuthUtils.requireAuthenticatedEmail();
        List<Map<String, Object>> originData = (List<Map<String, Object>>) requestData.get("origins");
        if (originData == null) {
            return ResponseEntity.badRequest().build();
        }

        List<OriginLocation> origins = originData.stream().map(data -> {
            OriginLocation origin = new OriginLocation();
            origin.setOwnerEmail(ownerEmail);
            origin.setName((String) data.get("name"));
            origin.setAddress((String) data.get("address"));
            origin.setLat(data.get("lat") != null ? ((Number) data.get("lat")).doubleValue() : null);
            origin.setLng(data.get("lng") != null ? ((Number) data.get("lng")).doubleValue() : null);
            return origin;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(originService.saveAll(ownerEmail, origins));
    }

    /**
     * PUT /api/origin-locations/{id}
     * Body: OriginLocation fields. Owner is the verified caller; query/body
     * ownerEmail (if present) is ignored.
     */
    @PutMapping("/{id}")
    public ResponseEntity<OriginLocation> update(
            @PathVariable Long id,
            @RequestParam(value = "ownerEmail", required = false) String ownerEmailIgnored,
            @RequestBody OriginLocation origin) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(originService.update(id, caller, origin));
    }

    /**
     * DELETE /api/origin-locations/{id}
     * Deletes the verified caller's origin. Query ownerEmail (if present) ignored.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestParam(value = "ownerEmail", required = false) String ownerEmailIgnored) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        originService.delete(id, caller);
        return ResponseEntity.noContent().build();
    }
}
