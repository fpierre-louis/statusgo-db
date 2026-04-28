package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.service.HouseholdManualMemberService;
import io.sitprep.sitprepapi.service.HouseholdManualMemberService.UpsertRequest;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manual household member CRUD. Removing cascades to drop any accompaniment
 * that referenced the manual member on either side (handled in service).
 */
@RestController
@RequestMapping("/api/households/{householdId}/manual-members")
public class HouseholdManualMemberResource {

    private final HouseholdManualMemberService service;

    public HouseholdManualMemberResource(HouseholdManualMemberService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<HouseholdManualMemberDto>> list(@PathVariable String householdId) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.list(householdId));
    }

    @PostMapping
    public ResponseEntity<HouseholdManualMemberDto> add(
            @PathVariable String householdId,
            @RequestBody UpsertRequest body) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.add(householdId, body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<HouseholdManualMemberDto> update(
            @PathVariable String householdId,
            @PathVariable String id,
            @RequestBody UpsertRequest body) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.update(householdId, id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(
            @PathVariable String householdId,
            @PathVariable String id) {
        AuthUtils.requireAuthenticatedEmail();
        service.remove(householdId, id);
        return ResponseEntity.noContent().build();
    }
}
