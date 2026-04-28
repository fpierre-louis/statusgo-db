package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.service.EmergencyContactGroupService;
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

    public EmergencyContactGroupResource(EmergencyContactGroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    public List<EmergencyContactGroup> getAllGroups() {
        return groupService.getAllGroups();
    }

    // FE: GET /api/emergency-groups/owner?ownerEmail=foo@bar.com
    @GetMapping("/owner")
    public List<EmergencyContactGroup> getGroupsByOwnerEmail(@RequestParam String ownerEmail) {
        return groupService.getGroupsByOwnerEmail(ownerEmail);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmergencyContactGroup> getGroupById(@PathVariable Long id) {
        return groupService.getGroupById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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
