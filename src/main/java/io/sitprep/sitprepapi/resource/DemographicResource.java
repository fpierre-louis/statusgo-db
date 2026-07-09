package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.dto.DemographicDto;
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
    public ResponseEntity<DemographicDto> saveDemographic(@RequestBody Demographic demographic) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        demographic.setOwnerEmail(caller); // override body — caller can only save their own
        return ResponseEntity.ok(DemographicDto.from(demographicService.saveDemographic(demographic)));
    }

    @GetMapping
    public ResponseEntity<List<DemographicDto>> getAllDemographics() {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(demographicService.getAllDemographics()
                .stream().map(DemographicDto::from).toList());
    }

    @GetMapping("/owner")
    public ResponseEntity<DemographicDto> getDemographicForCurrentUser() {
        AuthUtils.requireAuthenticatedEmail();
        return demographicService.getDemographicForCurrentUser()
                .map(DemographicDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/admin")
    public ResponseEntity<List<DemographicDto>> getDemographicsByAdminEmail() {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(demographicService.getDemographicsForCurrentAdmin()
                .stream().map(DemographicDto::from).toList());
    }
}
