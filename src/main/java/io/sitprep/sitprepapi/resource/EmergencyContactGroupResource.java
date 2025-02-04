package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.service.EmergencyContactGroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/emergency-groups")
public class EmergencyContactGroupResource {

    @Autowired
    private EmergencyContactGroupService groupService;

    @GetMapping
    public List<EmergencyContactGroup> getAllGroups() {
        return groupService.getAllGroups();
    }

    @GetMapping("/owner")
    public List<EmergencyContactGroup> getGroupsByOwnerEmail(@RequestParam String ownerEmail) {
        return groupService.getGroupsByOwnerEmail(ownerEmail);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmergencyContactGroup> getGroupById(@PathVariable Long id) {
        Optional<EmergencyContactGroup> group = groupService.getGroupById(id);
        return group.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }


    @PostMapping
    public EmergencyContactGroup createGroup(@RequestBody EmergencyContactGroup group) {
        return groupService.createGroup(group);
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmergencyContactGroup> updateGroup(@PathVariable Long id, @RequestBody EmergencyContactGroup updatedGroup) {
        return ResponseEntity.ok(groupService.updateGroup(id, updatedGroup));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        groupService.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }
}
