package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.service.GroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
public class GroupResource {

    @Autowired
    private GroupService groupService;

    @GetMapping("/admin/{email}")
    public List<Group> getGroupsByAdminEmail(@PathVariable String email) {
        return groupService.getGroupsByAdminEmail(email);
    }

    @PostMapping
    public Group createGroup(@RequestBody Group group) {
        return groupService.createGroup(group);
    }

    @PutMapping("/{groupId}")
    public ResponseEntity<Group> updateGroup(@PathVariable Long groupId, @RequestBody Group groupDetails) {
        Group updatedGroup = groupService.updateGroup(groupId, groupDetails);
        return ResponseEntity.ok(updatedGroup);
    }

    @DeleteMapping("/{groupId}")
    public void deleteGroup(@PathVariable Long groupId) {
        groupService.deleteGroup(groupId);
    }

    @GetMapping
    public List<Group> getAllGroups() {
        return groupService.getAllGroups();
    }

    @GetMapping("/{groupId}")
    public Group getGroupById(@PathVariable Long groupId) {
        return groupService.getGroupById(groupId);
    }
}
