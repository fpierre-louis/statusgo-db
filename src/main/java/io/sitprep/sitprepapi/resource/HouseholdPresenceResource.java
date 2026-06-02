package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.MemberPresenceFrame;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.HouseholdPresenceService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/households/{householdId}/presence")
public class HouseholdPresenceResource {

    private final HouseholdPresenceService service;
    private final HouseholdAccessService access;

    public HouseholdPresenceResource(
            HouseholdPresenceService service,
            HouseholdAccessService access
    ) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ResponseEntity<List<MemberPresenceFrame>> snapshot(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(service.snapshot(householdId));
    }
}
