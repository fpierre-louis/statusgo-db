package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupInvite;
import io.sitprep.sitprepapi.service.GroupInviteService;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for managing group invite tokens.
 *
 * <p>Routes:</p>
 * <ul>
 *   <li>{@code POST /api/groups/{groupId}/invites} — mint a new invite.</li>
 *   <li>{@code GET  /api/groups/{groupId}/invites} — list active invites.</li>
 *   <li>{@code POST /api/invites/{inviteId}/redeem} — authenticated token redeem.</li>
 *   <li>{@code POST /api/households/{householdId}/invites} — mint a household invite.</li>
 *   <li>{@code GET /api/household-invites/{inviteId}/resolve} — public household preview.</li>
 *   <li>{@code POST /api/household-invites/{inviteId}/redeem} — authenticated household redeem.</li>
 *   <li>{@code DELETE /api/invites/{inviteId}} — revoke an invite.</li>
 * </ul>
 *
 * <p>Auth: caller must be an admin or owner of the group. The
 * unauthenticated public-facing share path is at
 * {@code GET /share/i/{inviteId}} (handled in {@link ShareResource}).</p>
 */
@RestController
public class GroupInviteResource {

    private final GroupInviteService inviteService;
    private final GroupService groupService;

    public GroupInviteResource(GroupInviteService inviteService,
                               GroupService groupService) {
        this.inviteService = inviteService;
        this.groupService = groupService;
    }

    /**
     * Mint a new invite. Body fields are optional:
     * <ul>
     *   <li>{@code expiresInDays} — defaults to 7. Capped at 30 to
     *       discourage forever-living "share once, valid forever" links.</li>
     *   <li>{@code maxUses} — null/unset for unlimited. Pass 1 for
     *       single-use invites.</li>
     * </ul>
     */
    @PostMapping("/api/groups/{groupId}/invites")
    public ResponseEntity<GroupInvite> mint(
            @PathVariable String groupId,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String caller = requireAdminOf(groupId);

        Integer expiresInDays = readInt(body, "expiresInDays");
        Integer maxUses = readInt(body, "maxUses");

        Duration ttl = null;
        if (expiresInDays != null && expiresInDays > 0) {
            int days = Math.min(expiresInDays, 30);
            ttl = Duration.ofDays(days);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(
                inviteService.mint(groupId, caller, ttl, maxUses)
        );
    }

    @GetMapping("/api/groups/{groupId}/invites")
    public ResponseEntity<List<GroupInvite>> list(@PathVariable String groupId) {
        requireAdminOf(groupId);
        return ResponseEntity.ok(inviteService.listActive(groupId));
    }

    @PostMapping("/api/households/{householdId}/invites")
    public ResponseEntity<GroupInvite> mintHousehold(
            @PathVariable String householdId,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String caller = requireAdminOfHousehold(householdId);

        Integer expiresInDays = readInt(body, "expiresInDays");
        Integer maxUses = readInt(body, "maxUses");
        if (maxUses == null) {
            maxUses = 1;
        }

        Duration ttl = null;
        if (expiresInDays != null && expiresInDays > 0) {
            int days = Math.min(expiresInDays, 30);
            ttl = Duration.ofDays(days);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(
                inviteService.mint(householdId, caller, ttl, maxUses)
        );
    }

    /**
     * Public-facing JSON resolver for an invite token. Used by the
     * SPA dev-fallback route ({@code ShareInviteRedirect}) which
     * needs to map an invite id → groupId without UA-branching, and
     * by future surfaces that want to validate without redirecting.
     *
     * <p>Auth: public. Anyone with a token can resolve it to a sanitized
     * group id so the SPA can show the join screen before sign-in. The
     * privacy boundary is at redeem time, not here.</p>
     *
     * <p>Response shape:</p>
     * <pre>
     *   200 OK    → {"state":"OK","groupId":"abc-123"}
     *   404       → {"state":"NOT_FOUND"}
     *   410 Gone  → {"state":"EXPIRED" | "REVOKED" | "EXHAUSTED"}
     * </pre>
     */
    @GetMapping("/api/invites/{inviteId}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable String inviteId) {
        var result = inviteService.validate(inviteId);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("state", result.state().name());
        if (result.invite() != null && result.isOk()) {
            body.put("groupId", result.invite().getGroupId());
        }
        if (result.state() == GroupInviteService.InviteState.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        if (!result.isOk()) {
            // Expired / revoked / exhausted → 410 Gone with the
            // specific state in the body so the FE can surface
            // accurate copy.
            return ResponseEntity.status(HttpStatus.GONE).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/household-invites/{inviteId}/resolve")
    public ResponseEntity<Map<String, Object>> resolveHousehold(@PathVariable String inviteId) {
        var result = inviteService.previewHousehold(inviteId);
        Map<String, Object> body = householdPreviewBody(result);

        if (result.state() == GroupInviteService.InviteState.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        if (!result.isOk()) {
            return ResponseEntity.status(HttpStatus.GONE).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/invites/{inviteId}/redeem")
    public ResponseEntity<Map<String, Object>> redeem(@PathVariable String inviteId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        var result = inviteService.redeem(inviteId, caller);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("state", result.state().name());
        body.put("alreadyRedeemed", result.alreadyRedeemed());
        if (result.invite() != null) {
            body.put("groupId", result.invite().getGroupId());
        } else if (result.group() != null) {
            body.put("groupId", result.group().getGroupId());
        }
        if (result.group() != null) {
            body.put("group", result.group());
        }

        if (result.state() == GroupInviteService.InviteState.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        if (!result.isOk()) {
            return ResponseEntity.status(HttpStatus.GONE).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/household-invites/{inviteId}/redeem")
    public ResponseEntity<Map<String, Object>> redeemHousehold(@PathVariable String inviteId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        var result = inviteService.redeemHousehold(inviteId, caller);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("kind", "join_household");
        body.put("state", result.state().name());
        body.put("alreadyRedeemed", result.alreadyRedeemed());
        if (result.group() != null) {
            body.put("householdId", result.group().getGroupId());
            body.put("group", result.group());
        } else if (result.invite() != null && result.isOk()) {
            body.put("householdId", result.invite().getGroupId());
        }

        if (result.state() == GroupInviteService.InviteState.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        if (!result.isOk()) {
            return ResponseEntity.status(HttpStatus.GONE).body(body);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Revoke an invite. Auth: caller must be admin/owner of the
     * invite's group. Lookup the invite first to find the group, then
     * apply the same admin-check used for mint/list.
     */
    @DeleteMapping("/api/invites/{inviteId}")
    public ResponseEntity<Void> revoke(@PathVariable String inviteId) {
        var result = inviteService.validate(inviteId);
        if (result.invite() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found");
        }
        requireAdminOf(result.invite().getGroupId());
        inviteService.revoke(inviteId);
        return ResponseEntity.noContent().build();
    }

    // ---------- helpers ----------

    private String requireAdminOf(String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Group g;
        try {
            g = groupService.getGroupByPublicId(groupId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
        boolean isOwner = g.getOwnerEmail() != null
                && g.getOwnerEmail().equalsIgnoreCase(caller);
        boolean isAdmin = g.getAdminEmails() != null
                && g.getAdminEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(caller));
        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Group admin or owner role required");
        }
        return caller;
    }

    private String requireAdminOfHousehold(String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Group g;
        try {
            g = groupService.getGroupByPublicId(householdId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found");
        }
        if (!"Household".equalsIgnoreCase(g.getGroupType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found");
        }
        boolean isOwner = g.getOwnerEmail() != null
                && g.getOwnerEmail().equalsIgnoreCase(caller);
        boolean isAdmin = g.getAdminEmails() != null
                && g.getAdminEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(caller));
        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Household admin or owner role required");
        }
        return caller;
    }

    private static Map<String, Object> householdPreviewBody(GroupInviteService.HouseholdInvitePreview result) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("kind", "join_household");
        body.put("state", result.state().name());
        if (result.invite() != null) {
            body.put("expiresAt", result.invite().getExpiresAt());
        }
        if (result.household() != null && result.isOk()) {
            Group household = result.household();
            String name = household.getGroupName() == null || household.getGroupName().isBlank()
                    ? "your household"
                    : household.getGroupName();
            String inviter = household.getOwnerName() == null || household.getOwnerName().isBlank()
                    ? "Someone"
                    : household.getOwnerName();
            int members = household.getMemberEmails() == null ? 0 : household.getMemberEmails().size();
            body.put("title", "Join " + name);
            body.put("subtitle", inviter + " invited you to a private household plan on SitPrep.");
            body.put("householdName", name);
            body.put("inviterName", inviter);
            body.put("memberCount", members);
        }
        return body;
    }

    private static Integer readInt(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
