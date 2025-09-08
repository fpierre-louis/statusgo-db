package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.EmailRequest;
import io.sitprep.sitprepapi.service.GroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
@CrossOrigin(origins = "http://localhost:3000") // match your UserInfoResource
public class GroupResource {

    @Autowired
    private GroupService groupService;

    @GetMapping("/admin")
    public List<Group> getGroupsByAdminEmail() {
        return groupService.getGroupsForCurrentAdmin();
    }

    @PostMapping
    public Group createGroup(@RequestBody Group group) {
        System.out.println("Received group payload: " + group);
        return groupService.createGroup(group);
    }

    @PutMapping("/{groupId}")
    public ResponseEntity<Group> updateGroup(@PathVariable String groupId, @RequestBody Group groupDetails) {
        Group updatedGroup = groupService.updateGroupByPublicId(groupId, groupDetails);
        return ResponseEntity.ok(updatedGroup);
    }

    @DeleteMapping("/{groupId}")
    public void deleteGroup(@PathVariable String groupId) {
        groupService.deleteGroupByPublicId(groupId);
    }

    @GetMapping
    public List<Group> getAllGroups() {
        return groupService.getAllGroups();
    }

    @GetMapping("/{groupId}")
    public Group getGroupById(@PathVariable String groupId) {
        return groupService.getGroupByPublicId(groupId);
    }

    // ---------- NEW ROLE-AWARE ENDPOINTS ----------

    @PostMapping("/{groupId}/members/approve")
    public ResponseEntity<Group> approveMember(@PathVariable String groupId, @RequestBody EmailRequest req) {
        return ResponseEntity.ok(groupService.approveMember(groupId, req.email()));
    }

    @PostMapping("/{groupId}/members/reject")
    public ResponseEntity<Group> rejectPending(@PathVariable String groupId, @RequestBody EmailRequest req) {
        return ResponseEntity.ok(groupService.rejectPendingMember(groupId, req.email()));
    }

    @PostMapping("/{groupId}/members/remove")
    public ResponseEntity<Group> removeMember(@PathVariable String groupId, @RequestBody EmailRequest req) {
        return ResponseEntity.ok(groupService.removeMember(groupId, req.email()));
    }

    @PostMapping("/{groupId}/admins/add")
    public ResponseEntity<Group> addAdmin(@PathVariable String groupId, @RequestBody EmailRequest req) {
        return ResponseEntity.ok(groupService.addAdmin(groupId, req.email()));
    }

    @PostMapping("/{groupId}/admins/remove")
    public ResponseEntity<Group> removeAdmin(@PathVariable String groupId, @RequestBody EmailRequest req) {
        return ResponseEntity.ok(groupService.removeAdmin(groupId, req.email()));
    }

    @PostMapping("/{groupId}/owner/transfer")
    public ResponseEntity<Group> transferOwner(@PathVariable String groupId, @RequestBody EmailRequest req) {
        return ResponseEntity.ok(groupService.transferOwner(groupId, req.email()));
    }
}
