// src/main/java/io/sitprep/sitprepapi/resource/RSGroupResource.java
package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.RSGroup;
import io.sitprep.sitprepapi.domain.RSMemberRole;
import io.sitprep.sitprepapi.dto.RSGroupMemberDto;
import io.sitprep.sitprepapi.dto.RSGroupUpsertRequest;
import io.sitprep.sitprepapi.service.RSGroupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rs/groups")
@CrossOrigin(origins = "http://localhost:3000")
public class RSGroupResource {

    private final RSGroupService service;

    public RSGroupResource(RSGroupService service) {
        this.service = service;
    }

    // --------------------------
    // Groups
    // --------------------------

    @GetMapping("/public")
    public List<RSGroup> getPublic() {
        return service.getPublicGroups();
    }

    @GetMapping("/mine")
    public List<RSGroup> myGroups(@RequestParam(value = "email", required = false) String email) {
        return service.getGroupsForCurrentUserOrEmailFallback(email);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RSGroup> getById(@PathVariable String id) {
        return service.getById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RSGroup> create(@RequestBody RSGroupUpsertRequest incoming,
                                          @RequestParam(value = "email", required = false) String email) {
        return ResponseEntity.ok(service.createGroup(incoming, email));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RSGroup> update(@PathVariable String id,
                                          @RequestBody RSGroupUpsertRequest incoming,
                                          @RequestParam(value = "email", required = false) String email) {
        return ResponseEntity.ok(service.updateGroup(id, incoming, email));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @RequestParam(value = "email", required = false) String email) {
        service.deleteGroup(id, email);
        return ResponseEntity.noContent().build();
    }

    // --------------------------
    // Members (DTO)
    // --------------------------

    @GetMapping("/{id}/members")
    public List<RSGroupMemberDto> members(@PathVariable String id) {
        return service.getMembers(id);
    }

    @PostMapping("/{id}/members/invite")
    public ResponseEntity<RSGroupMemberDto> invite(@PathVariable String id,
                                                   @RequestBody Map<String, String> body,
                                                   @RequestParam(value = "email", required = false) String email) {
        String memberEmail = body == null ? null : body.get("memberEmail");
        return ResponseEntity.ok(service.inviteMember(id, memberEmail, email));
    }

    @PostMapping("/{id}/members/approve")
    public ResponseEntity<RSGroupMemberDto> approve(@PathVariable String id,
                                                    @RequestBody Map<String, String> body,
                                                    @RequestParam(value = "email", required = false) String email) {
        String memberEmail = body == null ? null : body.get("memberEmail");
        return ResponseEntity.ok(service.approveMember(id, memberEmail, email));
    }

    @PostMapping("/{id}/members/join")
    public ResponseEntity<RSGroupMemberDto> join(@PathVariable String id,
                                                 @RequestParam(value = "email", required = false) String email) {
        return ResponseEntity.ok(service.joinPublicGroup(id, email));
    }

    /** ✅ NEW: self-removal / leave group */
    @PostMapping("/{id}/members/leave")
    public ResponseEntity<Void> leave(@PathVariable String id,
                                      @RequestParam(value = "email", required = false) String email) {
        service.leaveGroup(id, email);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members/remove")
    public ResponseEntity<Void> remove(@PathVariable String id,
                                       @RequestBody Map<String, String> body,
                                       @RequestParam(value = "email", required = false) String email) {
        String memberEmail = body == null ? null : body.get("memberEmail");
        service.removeMember(id, memberEmail, email);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members/role")
    public ResponseEntity<RSGroupMemberDto> setRole(@PathVariable String id,
                                                    @RequestBody Map<String, String> body,
                                                    @RequestParam(value = "email", required = false) String email) {
        String memberEmail = body == null ? null : body.get("memberEmail");
        String roleStr = body == null ? null : body.get("role");
        RSMemberRole role = roleStr == null ? RSMemberRole.MEMBER : RSMemberRole.valueOf(roleStr);
        return ResponseEntity.ok(service.setRole(id, memberEmail, role, email));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}