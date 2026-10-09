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
 *
 * <p><b>That question was answered 2026-10-09:</b> PATCH and DELETE (rename,
 * remove the name, remove from household) are owner/admin only — 403 for a
 * plain member, enforced in the service through
 * {@link io.sitprep.sitprepapi.service.MemberActionPolicy#canEditNamedMembers}.
 * Adding (POST) stays open to any member.</p>
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
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(service.add(householdId, body, caller));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<HouseholdManualMemberDto> update(
            @PathVariable String householdId,
            @PathVariable String id,
            @RequestBody UpsertRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(service.update(householdId, id, body, caller));
    }

    /**
     * An owner or admin answers for a manual member. Body {@code {"status":
     * "SAFE" | "HELP" | "INJURED"}}. 200 with the member, carrying
     * {@code status}; 400 for any other value; 403 for a non-admin member (or
     * a non-member); 404 when the member is not in this household. No push.
     */
    @PostMapping("/{id}/status")
    public ResponseEntity<HouseholdManualMemberDto> setStatus(
            @PathVariable String householdId,
            @PathVariable String id,
            @RequestBody(required = false) StatusRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(service.setStatus(householdId, id, body == null ? null : body.status(), caller));
    }

    /** Clear it. Same gate; 200 with the member, {@code status: null}. */
    @DeleteMapping("/{id}/status")
    public ResponseEntity<HouseholdManualMemberDto> clearStatus(
            @PathVariable String householdId,
            @PathVariable String id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(service.clearStatus(householdId, id, caller));
    }

    public record StatusRequest(String status) {}

    /**
     * 204. Owner or admin (403 otherwise; 404 when not in this household).
     * {@code ?keepInCount=true} is "Remove name": the person stays counted and
     * becomes an unnamed placeholder in the same band. Without it, "Remove from
     * household": their band's count drops by one.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(
            @PathVariable String householdId,
            @PathVariable String id,
            @RequestParam(name = "keepInCount", defaultValue = "false") boolean keepInCount) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        service.remove(householdId, id, caller, keepInCount);
        return ResponseEntity.noContent().build();
    }
}
