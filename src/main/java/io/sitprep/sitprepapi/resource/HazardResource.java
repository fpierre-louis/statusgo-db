package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.HazardDto;
import io.sitprep.sitprepapi.service.HazardService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Neighbor hazard reports (hazard-reports gameplan HR1, V87).
 *
 * <ul>
 *   <li>{@code POST /api/hazards} — report. 422 when farther than 3.2 km from
 *       the reporter's fix (or no fix), 429 past 5 an hour, 403 for guests.</li>
 *   <li>{@code POST /api/hazards/{id}/vote} {@code {"vote":"still"|"gone"}}</li>
 *   <li>{@code POST /api/hazards/{id}/official|clear} {@code {"agencyGroupId"}}
 *       — the agency area-alert gate.</li>
 *   <li>{@code GET /api/hazards?minLat&minLng&maxLat&maxLng} — PUBLIC, like
 *       the community map; never names a reporter.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/hazards")
public class HazardResource {

    public record VoteRequest(String vote) {}
    public record AgencyRequest(String agencyGroupId) {}

    private final HazardService hazards;

    public HazardResource(HazardService hazards) {
        this.hazards = hazards;
    }

    @PostMapping
    public ResponseEntity<HazardDto> report(@RequestBody HazardService.ReportRequest body) {
        return ResponseEntity.ok(hazards.report(body, AuthUtils.requireAuthenticatedEmail()));
    }

    @PostMapping("/{id}/vote")
    public ResponseEntity<HazardDto> vote(@PathVariable Long id, @RequestBody VoteRequest body) {
        return ResponseEntity.ok(hazards.vote(id, body == null ? null : body.vote(), AuthUtils.requireAuthenticatedEmail()));
    }

    @PostMapping("/{id}/official")
    public ResponseEntity<HazardDto> official(@PathVariable Long id, @RequestBody AgencyRequest body) {
        return ResponseEntity.ok(hazards.markOfficial(id, body == null ? null : body.agencyGroupId(), AuthUtils.requireAuthenticatedEmail()));
    }

    @PostMapping("/{id}/clear")
    public ResponseEntity<HazardDto> clear(@PathVariable Long id, @RequestBody AgencyRequest body) {
        return ResponseEntity.ok(hazards.clear(id, body == null ? null : body.agencyGroupId(), AuthUtils.requireAuthenticatedEmail()));
    }

    @GetMapping
    public ResponseEntity<List<HazardDto>> inBox(@RequestParam double minLat, @RequestParam double minLng,
                                                 @RequestParam double maxLat, @RequestParam double maxLng) {
        return ResponseEntity.ok(hazards.inBox(minLat, minLng, maxLat, maxLng, AuthUtils.getCurrentUserEmail()));
    }
}
