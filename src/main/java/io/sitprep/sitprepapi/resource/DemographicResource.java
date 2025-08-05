package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.service.DemographicService;
import io.sitprep.sitprepapi.util.AuthUtils;
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
        return ResponseEntity.ok(demographicService.saveDemographic(demographic));
    }

    @GetMapping
    public ResponseEntity<List<Demographic>> getAllDemographics() {
        return ResponseEntity.ok(demographicService.getAllDemographics());
    }

    @GetMapping("/owner")
    public ResponseEntity<Demographic> getDemographicForCurrentUser() {
        return demographicService.getDemographicForCurrentUser()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/admin")
    public ResponseEntity<List<Demographic>> getDemographicsByAdminEmail() {
        return ResponseEntity.ok(demographicService.getDemographicsForCurrentAdmin());
    }
}
