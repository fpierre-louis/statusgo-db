package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.EmergencySupportDtos.*;
import io.sitprep.sitprepapi.service.EmergencySupportService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RC-3 Phase 2 — Emergency Need Profiles and prepared Support Plans.
 *
 * <p><b>Every route is authenticated and household-scoped.</b> There is no
 * anonymous path and no group path: a group admin must not receive a member's
 * support needs because that person joined their group. The actor is always
 * token-derived, never a body or query parameter — the rule this codebase
 * already enforces everywhere else that authority matters.</p>
 *
 * <p>Reads need household membership; writes need household admin, or the
 * subject editing their own profile. {@link EmergencySupportService} owns both
 * gates.</p>
 */
@RestController
@RequestMapping("/api/households/{householdId}/support")
public class EmergencySupportResource {

    private final EmergencySupportService service;

    public EmergencySupportResource(EmergencySupportService service) {
        this.service = service;
    }

    /** Profiles + assignments in one round trip — the editor and the plan tab both need both. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        List<SupportProfileDto> profiles = service.listProfiles(householdId, caller);
        List<SupportAssignmentDto> assignments = service.listAssignments(householdId, caller);
        return ResponseEntity.ok(Map.of("profiles", profiles, "assignments", assignments));
    }

    @PutMapping("/profiles/{subjectType}/{subjectId}")
    public ResponseEntity<SupportProfileDto> upsertProfile(
            @PathVariable String householdId,
            @PathVariable String subjectType,
            @PathVariable String subjectId,
            @RequestBody SupportProfileRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(
                service.upsertProfile(householdId, subjectType, subjectId, body, caller));
    }

    /** Removes the profile and the assignments that only made sense alongside it. */
    @DeleteMapping("/profiles/{subjectType}/{subjectId}")
    public ResponseEntity<Void> deleteProfile(
            @PathVariable String householdId,
            @PathVariable String subjectType,
            @PathVariable String subjectId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        service.deleteProfile(householdId, subjectType, subjectId, caller);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/assignments/{subjectType}/{subjectId}/{role}")
    public ResponseEntity<SupportAssignmentDto> upsertAssignment(
            @PathVariable String householdId,
            @PathVariable String subjectType,
            @PathVariable String subjectId,
            @PathVariable String role,
            @RequestBody SupportAssignmentRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(
                service.upsertAssignment(householdId, subjectType, subjectId, role, body, caller));
    }

    @DeleteMapping("/assignments/{subjectType}/{subjectId}/{role}")
    public ResponseEntity<Void> deleteAssignment(
            @PathVariable String householdId,
            @PathVariable String subjectType,
            @PathVariable String subjectId,
            @PathVariable String role) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        service.deleteAssignment(householdId, subjectType, subjectId, role, caller);
        return ResponseEntity.noContent().build();
    }
}
