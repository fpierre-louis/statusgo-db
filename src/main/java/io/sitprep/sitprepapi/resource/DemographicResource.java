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

    // GET /api/demographics (dump-all) was DELETED 2026-08-24.
    //
    // Auth-only, and it returned every household's infant / kid / teen / adult
    // and pet counts alongside ownerEmail and householdId — a queryable index of
    // which homes contain small children, and an ownerEmail → householdId map
    // that made every id-taking household route below targetable. No FE caller.
    //
    // Do not re-add. A cross-tenant read belongs behind
    // PlatformPermission.VIEW_PII with an audit record (UserInfoResource:80-81).

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
