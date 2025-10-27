package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.service.EmergencyContactGroupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    // FE sends ownerEmail in body (MVP, no JWT)
    @PostMapping
    public ResponseEntity<EmergencyContactGroup> createGroup(@RequestBody EmergencyContactGroup group) {
        EmergencyContactGroup saved = groupService.createGroup(group);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmergencyContactGroup> updateGroup(
            @PathVariable Long id,
            @RequestBody EmergencyContactGroup updatedGroup
    ) {
        EmergencyContactGroup saved = groupService.updateGroup(id, updatedGroup);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        groupService.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }
}
