package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.HouseholdPetDto;
import io.sitprep.sitprepapi.service.HouseholdPetService;
import io.sitprep.sitprepapi.service.HouseholdPetService.UpsertRequest;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/households/{householdId}/pets")
public class HouseholdPetResource {

    private final HouseholdPetService service;

    public HouseholdPetResource(HouseholdPetService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<HouseholdPetDto>> list(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.list(caller, householdId));
    }

    @PostMapping
    public ResponseEntity<HouseholdPetDto> add(
            @PathVariable String householdId,
            @RequestBody UpsertRequest body
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.add(caller, householdId, body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<HouseholdPetDto> update(
            @PathVariable String householdId,
            @PathVariable String id,
            @RequestBody UpsertRequest body
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.update(caller, householdId, id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(
            @PathVariable String householdId,
            @PathVariable String id
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        service.remove(caller, householdId, id);
        return ResponseEntity.noContent().build();
    }
}
