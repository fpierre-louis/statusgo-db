package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.service.OriginLocationService;
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
     * GET /api/origin-locations?ownerEmail=user@example.com
     */
    @GetMapping
    public ResponseEntity<List<OriginLocation>> getByOwner(
            @RequestParam("ownerEmail") String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(originService.getByOwnerEmail(ownerEmail));
    }

    /**
     * POST /api/origin-locations/bulk
     * Body:
     * {
     *   "ownerEmail": "user@example.com",
     *   "origins": [{ "name":"Home","address":"...","lat":1.0,"lng":2.0 }, ...]
     * }
     * Replaces all existing origins for the user.
     */
    @PostMapping("/bulk")
    public ResponseEntity<List<OriginLocation>> saveAllOrigins(@RequestBody Map<String, Object> requestData) {
        String ownerEmail = (String) requestData.get("ownerEmail");
        List<Map<String, Object>> originData = (List<Map<String, Object>>) requestData.get("origins");

        if (ownerEmail == null || ownerEmail.isBlank() || originData == null) {
            return ResponseEntity.badRequest().build();
        }

        List<OriginLocation> origins = originData.stream().map(data -> {
            OriginLocation origin = new OriginLocation();
            origin.setOwnerEmail(ownerEmail); // enforce owner from top-level
            origin.setName((String) data.get("name"));
            origin.setAddress((String) data.get("address"));
            origin.setLat(data.get("lat") != null ? ((Number) data.get("lat")).doubleValue() : null);
            origin.setLng(data.get("lng") != null ? ((Number) data.get("lng")).doubleValue() : null);
            return origin;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(originService.saveAll(ownerEmail, origins));
    }

    /**
     * PUT /api/origin-locations/{id}?ownerEmail=user@example.com
     * Body: OriginLocation fields (name, address, lat, lng)
     */
    @PutMapping("/{id}")
    public ResponseEntity<OriginLocation> update(
            @PathVariable Long id,
            @RequestParam("ownerEmail") String ownerEmail,
            @RequestBody OriginLocation origin) {

        if (ownerEmail == null || ownerEmail.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(originService.update(id, ownerEmail, origin));
    }

    /**
     * DELETE /api/origin-locations/{id}?ownerEmail=user@example.com
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestParam("ownerEmail") String ownerEmail) {

        if (ownerEmail == null || ownerEmail.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        originService.delete(id, ownerEmail);
        return ResponseEntity.noContent().build();
    }
}
