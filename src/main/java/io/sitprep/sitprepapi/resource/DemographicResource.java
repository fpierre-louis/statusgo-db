package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.service.DemographicService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/demographics")
@CrossOrigin(origins = "http://localhost:3000")
public class DemographicResource {

    private final DemographicService demographicService;

    public DemographicResource(DemographicService demographicService) {
        this.demographicService = demographicService;
    }

    @PostMapping
    public ResponseEntity<Demographic> saveDemographic(@RequestBody Demographic demographic) {
        Demographic savedDemographic = demographicService.saveDemographic(demographic);
        return ResponseEntity.ok(savedDemographic);
    }

    @GetMapping
    public ResponseEntity<List<Demographic>> getAllDemographics() {
        return ResponseEntity.ok(demographicService.getAllDemographics());
    }

    @GetMapping("/owner")
    public ResponseEntity<List<Demographic>> getDemographicsByOwnerEmail(@RequestParam String ownerEmail) {
        return ResponseEntity.ok(demographicService.getDemographicsByOwnerEmail(ownerEmail));
    }

    @GetMapping("/admin")
    public ResponseEntity<List<Demographic>> getDemographicsByAdminEmail(@RequestParam String adminEmail) {
        return ResponseEntity.ok(demographicService.getDemographicsByAdminEmail(adminEmail));
    }
}