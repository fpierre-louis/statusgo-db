package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.HouseholdManualMemberService;
import io.sitprep.sitprepapi.service.HouseholdManualMemberService.UpsertRequest;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manual household member CRUD. Removing cascades to drop any accompaniment
 * that referenced the manual member on either side (handled in service).
 *
 * <p><b>Household membership is required on every route (2026-08-24).</b> These
 * endpoints previously checked only that the caller was signed in, so any
 * account could pass any household id and read another family's children by
 * name and age — or delete them, taking their accompaniment links with them.
 * Ids are not secret; several endpoints hand them out.</p>
 *
 * <p>The gate is membership, not admin, on writes too. That is the boundary the
 * hole was in: whether a non-admin <em>member</em> should be able to edit a
 * manual member is a separate product question, and quietly tightening it here
 * would break households where a non-admin parent adds a child today.</p>
 */
@RestController
@RequestMapping("/api/households/{householdId}/manual-members")
public class HouseholdManualMemberResource {

    private final HouseholdManualMemberService service;
    private final HouseholdAccessService access;

    public HouseholdManualMemberResource(HouseholdManualMemberService service,
                                         HouseholdAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ResponseEntity<List<HouseholdManualMemberDto>> list(@PathVariable String householdId) {
        access.requireCanReadHousehold(AuthUtils.requireAuthenticatedEmail(), householdId);
        return ResponseEntity.ok(service.list(householdId));
    }

    @PostMapping
    public ResponseEntity<HouseholdManualMemberDto> add(
            @PathVariable String householdId,
            @RequestBody UpsertRequest body) {
        access.requireCanReadHousehold(AuthUtils.requireAuthenticatedEmail(), householdId);
        return ResponseEntity.ok(service.add(householdId, body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<HouseholdManualMemberDto> update(
            @PathVariable String householdId,
            @PathVariable String id,
            @RequestBody UpsertRequest body) {
        access.requireCanReadHousehold(AuthUtils.requireAuthenticatedEmail(), householdId);
        return ResponseEntity.ok(service.update(householdId, id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(
            @PathVariable String householdId,
            @PathVariable String id) {
        access.requireCanReadHousehold(AuthUtils.requireAuthenticatedEmail(), householdId);
        service.remove(householdId, id);
        return ResponseEntity.noContent().build();
    }
}
