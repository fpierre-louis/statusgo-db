package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.service.EmergencyContactGroupService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/emergency-groups")
@CrossOrigin(origins = "http://localhost:3000")
public class EmergencyContactGroupResource {

    private final EmergencyContactGroupService groupService;
    private final HouseholdAccessService access;

    public EmergencyContactGroupResource(EmergencyContactGroupService groupService,
                                         HouseholdAccessService access) {
        this.groupService = groupService;
        this.access = access;
    }

    @GetMapping
    public List<EmergencyContactGroup> getAllGroups() {
        // Dump-all is admin-only conceptually. Require auth at minimum;
        // until a platform-admin role exists, any signed-in user can see
        // it (which is still better than fully open).
        AuthUtils.requireAuthenticatedEmail();
        return groupService.getAllGroups();
    }

    /**
     * Fetch a user's contact groups. Household plan-sharing has members
     * reading the household head's contacts, so the {@code ownerEmail}
     * param can target a different user — but only if the caller shares
     * a household with them (or is the owner themselves). Otherwise 403.
     */
    @GetMapping("/owner")
    public List<EmergencyContactGroup> getGroupsByOwnerEmail(@RequestParam String ownerEmail) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadPlanDataFor(caller, ownerEmail);
        return groupService.getGroupsByOwnerEmail(ownerEmail);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmergencyContactGroup> getGroupById(@PathVariable Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<EmergencyContactGroup> opt = groupService.getGroupById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        // 404 (not 403) when the caller can't see this group — don't leak
        // which ids exist in someone else's household.
        EmergencyContactGroup g = opt.get();
        if (!access.canReadPlanDataFor(caller, g.getOwnerEmail())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(g);
    }

    @PostMapping
    public ResponseEntity<EmergencyContactGroup> createGroup(@RequestBody EmergencyContactGroup group) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        group.setOwnerEmail(caller); // override body
        EmergencyContactGroup saved = groupService.createGroup(group);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmergencyContactGroup> updateGroup(
            @PathVariable Long id,
            @RequestBody EmergencyContactGroup updatedGroup
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        ensureOwns(id, caller);
        updatedGroup.setOwnerEmail(caller); // owner is immutable
        EmergencyContactGroup saved = groupService.updateGroup(id, updatedGroup);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        ensureOwns(id, caller);
        groupService.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }

    private void ensureOwns(Long id, String caller) {
        Optional<EmergencyContactGroup> existing = groupService.getGroupById(id);
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String owner = existing.get().getOwnerEmail();
        if (owner == null || !owner.equalsIgnoreCase(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Emergency contact group belongs to a different user");
        }
    }
}
