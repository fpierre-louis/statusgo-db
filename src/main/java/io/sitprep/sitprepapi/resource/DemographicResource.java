package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.service.DemographicService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

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
    public ResponseEntity<Demographic> getDemographicByOwnerEmail() {
        String ownerEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        Optional<Demographic> demographic = demographicService.getDemographicByOwnerEmail(ownerEmail);
        return demographic.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/admin")
    public ResponseEntity<List<Demographic>> getDemographicsByAdminEmail() {
        String adminEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.ok(demographicService.getDemographicsByAdminEmail(adminEmail));
    }

}
