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

    @PostMapping("/bulk")
    public ResponseEntity<List<OriginLocation>> saveAllOrigins(@RequestBody Map<String, Object> requestData) {
        String ownerEmail = (String) requestData.get("ownerEmail");
        List<Map<String, Object>> originData = (List<Map<String, Object>>) requestData.get("origins");

        if (ownerEmail == null || originData == null || originData.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<OriginLocation> origins = originData.stream().map(data -> {
            OriginLocation origin = new OriginLocation();
            origin.setOwnerEmail(ownerEmail);
            origin.setAddress((String) data.get("address"));
            origin.setLat(data.get("lat") != null ? ((Number) data.get("lat")).doubleValue() : null);
            origin.setLng(data.get("lng") != null ? ((Number) data.get("lng")).doubleValue() : null);
            return origin;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(originService.saveAll(ownerEmail, origins));
    }

    @GetMapping
    public ResponseEntity<List<OriginLocation>> getByUser(@RequestParam String ownerEmail) {
        return ResponseEntity.ok(originService.getByOwnerEmail(ownerEmail));
    }


    @PutMapping("/{id}")
    public ResponseEntity<OriginLocation> update(@PathVariable Long id, @RequestBody OriginLocation origin) {
        return ResponseEntity.ok(originService.update(id, origin));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        originService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
