package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.EmailRequest;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Group CRUD + membership/role ops.
 *
 * <p>Phase E enforcement live on every WRITE. Authorization layered:</p>
 * <ul>
 *   <li>Create — must be authenticated; the caller becomes owner.</li>
 *   <li>Update / member ops — admin or owner of the group.</li>
 *   <li>Delete / owner transfer — owner only.</li>
 * </ul>
 *
 * <p>Reads now require a verified token. The community-discover endpoint
 * is the right surface for unauthenticated browsing and lives at
 * {@code /api/community/discover} — anything that needs a public preview
 * of a single group should carve a dedicated read-only endpoint instead
 * of opening this one back up.</p>
 */
@RestController
@RequestMapping("/api/groups")
@CrossOrigin(origins = "http://localhost:3000")
public class GroupResource {

    @Autowired
    private GroupService groupService;

    @GetMapping("/admin")
    public List<Group> getGroupsByAdminEmail() {
        AuthUtils.requireAuthenticatedEmail();
        return groupService.getGroupsForCurrentAdmin();
    }

    @PostMapping
    public Group createGroup(@RequestBody Group group) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        // Force the creator into the owner / admin / member slots so they
        // can never create a group they aren't part of.
        group.setOwnerEmail(caller);
        if (group.getAdminEmails() == null) group.setAdminEmails(List.of());
        if (!containsCaseInsensitive(group.getAdminEmails(), caller)) {
            group.setAdminEmails(append(group.getAdminEmails(), caller));
        }
        if (group.getMemberEmails() == null) group.setMemberEmails(List.of());
        if (!containsCaseInsensitive(group.getMemberEmails(), caller)) {
            group.setMemberEmails(append(group.getMemberEmails(), caller));
        }
        return groupService.createGroup(group);
    }

    @PutMapping("/{groupId}")
    public ResponseEntity<Group> updateGroup(@PathVariable String groupId, @RequestBody Group groupDetails) {
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.updateGroupByPublicId(groupId, groupDetails));
    }

    @DeleteMapping("/{groupId}")
    public void deleteGroup(@PathVariable String groupId) {
        requireOwnerOf(groupId);
        groupService.deleteGroupByPublicId(groupId);
    }

    /**
     * Lightweight uniqueness check for the group-create flows. Replaces
     * the previous pattern of FE pulling the entire groups table on
     * every keystroke and scanning in memory — that was the worst
     * offender on the LAUNCH_READINESS.md "no render-blocking dump-
     * everything endpoints on critical paths" item, even though
     * group-create isn't quite a critical path.
     *
     * <p>Both query params are optional. If only {@code name} is
     * provided, {@code codeTaken} is null (and vice versa) so the FE
     * can debounce per-field independently. Empty / blank values
     * short-circuit to false rather than hitting the DB.</p>
     */
    @GetMapping("/availability")
    public Map<String, Boolean> checkAvailability(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "code", required = false) String code) {
        AuthUtils.requireAuthenticatedEmail();
        Map<String, Boolean> out = new HashMap<>();
        if (name != null && !name.isBlank()) {
            out.put("nameTaken", groupService.isGroupNameTaken(name.trim()));
        }
        if (code != null && !code.isBlank()) {
            out.put("codeTaken", groupService.isGroupCodeTaken(code.trim()));
        }
        return out;
    }

    @GetMapping("/{groupId}")
    public Group getGroupById(@PathVariable String groupId) {
        AuthUtils.requireAuthenticatedEmail();
        return groupService.getGroupByPublicId(groupId);
    }

    // ---------- Membership / role ops (admin or owner) ----------

    @PostMapping("/{groupId}/members/approve")
    public ResponseEntity<Group> approveMember(@PathVariable String groupId, @RequestBody EmailRequest req) {
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.approveMember(groupId, req.email()));
    }

    @PostMapping("/{groupId}/members/reject")
    public ResponseEntity<Group> rejectPending(@PathVariable String groupId, @RequestBody EmailRequest req) {
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.rejectPendingMember(groupId, req.email()));
    }

    @PostMapping("/{groupId}/members/remove")
    public ResponseEntity<Group> removeMember(@PathVariable String groupId, @RequestBody EmailRequest req) {
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.removeMember(groupId, req.email()));
    }

    @PostMapping("/{groupId}/admins/add")
    public ResponseEntity<Group> addAdmin(@PathVariable String groupId, @RequestBody EmailRequest req) {
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.addAdmin(groupId, req.email()));
    }

    @PostMapping("/{groupId}/admins/remove")
    public ResponseEntity<Group> removeAdmin(@PathVariable String groupId, @RequestBody EmailRequest req) {
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.removeAdmin(groupId, req.email()));
    }

    @PostMapping("/{groupId}/owner/transfer")
    public ResponseEntity<Group> transferOwner(@PathVariable String groupId, @RequestBody EmailRequest req) {
        requireOwnerOf(groupId);
        return ResponseEntity.ok(groupService.transferOwner(groupId, req.email()));
    }

    // ---------- Authorization helpers ----------

    /** Caller must be admin or owner of the group. Throws 401/403/404. */
    private void requireAdminOf(String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Group g = lookup(groupId);
        boolean isOwner = g.getOwnerEmail() != null
                && g.getOwnerEmail().equalsIgnoreCase(caller);
        boolean isAdmin = containsCaseInsensitive(g.getAdminEmails(), caller);
        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Group admin or owner role required");
        }
    }

    /** Caller must be the group's owner. Throws 401/403/404. */
    private void requireOwnerOf(String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Group g = lookup(groupId);
        if (g.getOwnerEmail() == null || !g.getOwnerEmail().equalsIgnoreCase(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Group owner role required");
        }
    }

    private Group lookup(String groupId) {
        try {
            return groupService.getGroupByPublicId(groupId);
        } catch (RuntimeException e) {
            // GroupService throws RuntimeException("Group not found...") today.
            // Convert to a 404 so we don't leak whether the id is taken.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
    }

    private static boolean containsCaseInsensitive(List<String> list, String needle) {
        if (list == null || list.isEmpty() || needle == null) return false;
        for (String e : list) {
            if (e != null && e.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }

    private static List<String> append(List<String> list, String value) {
        java.util.List<String> out = new java.util.ArrayList<>(list);
        out.add(value);
        return out;
    }
}
