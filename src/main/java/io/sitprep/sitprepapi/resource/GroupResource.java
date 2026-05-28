package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.GroupPermission;
import io.sitprep.sitprepapi.constant.GroupRole;
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

    @Autowired
    private io.sitprep.sitprepapi.repo.GroupReadStateRepo groupReadStateRepo;

    @Autowired
    private io.sitprep.sitprepapi.service.GroupMuteService groupMuteService;

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

    /**
     * Public-safe preview of a group for the join-confirmation page.
     * Returns identity, purpose, scale, owner name, alert state, and
     * the viewer's relationship to the group (owner / admin / member /
     * pending / none) without exposing member or admin emails.
     *
     * <p>Use this anywhere a non-member is shown a group: the discover
     * page's "view before joining" flow, the {@code /joingroup} invite-
     * link confirmation page. The full {@link Group} endpoint above
     * leaks the roster and should only be used by members.</p>
     *
     * <p>Deliberately NOT auth-gated: an invite-link recipient lands
     * here BEFORE signing in, so requiring a token would 401 the very
     * flow this endpoint exists to serve. The DTO is already sanitized
     * (no member/admin emails). A verified token, when present, lets
     * the service compute {@code viewerStatus}; anonymous callers get
     * {@code viewerStatus = NONE}.</p>
     */
    @GetMapping("/{groupId}/preview")
    public ResponseEntity<io.sitprep.sitprepapi.dto.GroupPreviewDto> getGroupPreview(
            @PathVariable String groupId
    ) {
        String viewer = AuthUtils.getCurrentUserEmail();
        return ResponseEntity.ok(groupService.getGroupPreview(groupId, viewer));
    }

    // ---------- Membership / role ops (admin or owner) ----------

    /**
     * Self-service join. Any authenticated user can call this:
     * <ul>
     *   <li><b>Public group</b> → caller appended to {@code memberEmails}
     *       (instant join).</li>
     *   <li><b>Private group</b> → caller appended to
     *       {@code pendingMemberEmails} (request to join). The existing
     *       {@code pending_member} notification path fires admins'
     *       lock-screen Approve / Decline action buttons.</li>
     * </ul>
     *
     * <p>Idempotent: a second call when already a member / pending is a
     * no-op (returns the current group state).</p>
     *
     * <p>Replaces the previous pattern of having the FE call
     * {@code PUT /groups/{id}} with a hand-edited memberEmails array.
     * That path is admin-gated and 403'd for normal users — the join
     * flow has been broken at the auth layer.</p>
     */
    @PostMapping("/{groupId}/members/join")
    public ResponseEntity<Group> selfJoin(@PathVariable String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        try {
            return ResponseEntity.ok(groupService.selfJoin(groupId, caller));
        } catch (RuntimeException e) {
            // GroupService.getGroupByPublicId throws RuntimeException on
            // missing-group; convert to a clean 404 so the FE can surface
            // "this circle isn't available" rather than a generic 500.
            // Stale joingroupId cache values (e.g. an old test id like
            // "100") were producing 500s pre-2026-05-09.
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("group not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
            }
            throw e;
        }
    }

    /**
     * Send a non-emergency "please check in" ping to a group's members.
     * Phase 1 of docs/BUSINESS_MODEL.md — the family check-in primitive.
     * Does NOT flip the group's alert state (that's {@code PUT /api/groups}
     * with {@code alert: "Active"}). Authorization lives in
     * {@code GroupService.requestCheckIn} — any member for households,
     * admin/owner only for larger org groups. Returns 204 on success,
     * 403 when the caller isn't allowed, 404 when the group is missing.
     */
    @PostMapping("/{groupId}/check-in-request")
    public ResponseEntity<Void> requestCheckIn(@PathVariable String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        try {
            groupService.requestCheckIn(groupId, caller);
            return ResponseEntity.noContent().build();
        } catch (SecurityException se) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, se.getMessage());
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("group not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
            }
            throw e;
        }
    }

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

    /**
     * Set the group's organization plan tier — Phase 4 of
     * docs/BUSINESS_MODEL.md. Self-serve and unpaid for now (Stripe
     * billing lands later). <b>Owner only</b> ({@code MANAGE_PLAN}) —
     * the plan is a billing commitment, so it sits above the admin
     * role. Body: {@code {"planTier": "GROUP"}}. An unknown / missing
     * value normalizes to {@code FREE} in {@code GroupService.setPlanTier}.
     */
    @PatchMapping("/{groupId}/plan")
    public ResponseEntity<Group> setPlan(@PathVariable String groupId,
                                         @RequestBody Map<String, String> body) {
        requirePermission(groupId, GroupPermission.MANAGE_PLAN);
        String tier = body == null ? null : body.get("planTier");
        return ResponseEntity.ok(groupService.setPlanTier(groupId, tier));
    }

    /**
     * Mark this circle "read" for the authenticated viewer — used by the
     * Circles list page to clear the unread badge when the user opens
     * the circle. Upserts a {@code GroupReadState} (one row per
     * user+group) with {@code lastReadAt=now}; the
     * {@code MeDto.GroupSummary.unreadCount} surfaces the delta between
     * this timestamp and incoming {@code GroupPost.timestamp}.
     */
    @PostMapping("/{groupId}/read")
    public ResponseEntity<Void> markGroupRead(@PathVariable String groupId) {
        String email = AuthUtils.requireAuthenticatedEmail();
        io.sitprep.sitprepapi.domain.GroupReadState s = groupReadStateRepo
                .findByUserEmailIgnoreCaseAndGroupId(email, groupId)
                .orElseGet(() -> {
                    var ns = new io.sitprep.sitprepapi.domain.GroupReadState();
                    ns.setUserEmail(email);
                    ns.setGroupId(groupId);
                    return ns;
                });
        s.setLastReadAt(java.time.Instant.now());
        groupReadStateRepo.save(s);
        return ResponseEntity.noContent().build();
    }

    /**
     * Read the viewer's mute pref for this circle. Returns
     * {@code {mutedUntil: "ISO-8601" | null}}; an absent pref reads
     * as {@code mutedUntil: null} so the FE doesn't have to handle a
     * 404 separately. Used by the long-press Mute sheet to seed its
     * current-state line ("Muted for 3 more hours").
     */
    @GetMapping("/{groupId}/mute")
    public ResponseEntity<java.util.Map<String, Object>> getGroupMute(@PathVariable String groupId) {
        String email = AuthUtils.requireAuthenticatedEmail();
        java.time.Instant until = groupMuteService.getMute(email, groupId)
                .map(io.sitprep.sitprepapi.domain.GroupMutePref::getMutedUntil)
                .orElse(null);
        return ResponseEntity.ok(java.util.Collections.singletonMap("mutedUntil", until));
    }

    /**
     * Upsert the viewer's mute pref for this circle. Body shape:
     * {@code {"mutedUntil": "ISO-8601"}} for a deadline,
     * {@code {"mutedUntil": "indefinite"}} for "until I turn it
     * back on" (resolves to {@link io.sitprep.sitprepapi.service.GroupMuteService#INDEFINITE}),
     * or {@code {"mutedUntil": null}} to clear. The MeDto's
     * {@code groups.mutedUntil} surfaces the result so other surfaces
     * (card bell, long-press subtitle) update on next /me hit.
     */
    @PutMapping("/{groupId}/mute")
    public ResponseEntity<java.util.Map<String, Object>> setGroupMute(
            @PathVariable String groupId,
            @RequestBody(required = false) java.util.Map<String, Object> body
    ) {
        String email = AuthUtils.requireAuthenticatedEmail();
        Object raw = body == null ? null : body.get("mutedUntil");
        java.time.Instant until = null;
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.equalsIgnoreCase("indefinite")) {
                until = io.sitprep.sitprepapi.service.GroupMuteService.INDEFINITE;
            } else if (!trimmed.isEmpty()) {
                try {
                    until = java.time.Instant.parse(trimmed);
                } catch (java.time.format.DateTimeParseException e) {
                    return ResponseEntity.badRequest().build();
                }
            }
        }
        var pref = groupMuteService.setMute(email, groupId, until);
        return ResponseEntity.ok(java.util.Collections.singletonMap("mutedUntil", pref.getMutedUntil()));
    }

    /**
     * Upsert the viewer's daily quiet-hours window for this circle.
     * Body shape:
     * <pre>
     *   { "start": 1320, "end": 420, "timezone": "America/New_York" }
     *   { "start": null, "end": null }    // clears the window
     * </pre>
     * {@code start} / {@code end} are minutes from midnight in the
     * supplied timezone (0..1439). {@code start > end} means the
     * window crosses midnight (22:00→07:00). Returns the saved
     * pref's window + timezone so the FE can reconcile without a
     * second fetch.
     */
    @PutMapping("/{groupId}/quiet-hours")
    public ResponseEntity<java.util.Map<String, Object>> setGroupQuietHours(
            @PathVariable String groupId,
            @RequestBody(required = false) java.util.Map<String, Object> body
    ) {
        String email = AuthUtils.requireAuthenticatedEmail();
        Integer start = readMinute(body, "start");
        Integer end = readMinute(body, "end");
        Object tzRaw = body == null ? null : body.get("timezone");
        String tz = tzRaw instanceof String s ? s.trim() : null;
        try {
            var pref = groupMuteService.setQuietHours(email, groupId, start, end, tz);
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("quietStart", pref.getQuietStart());
            out.put("quietEnd", pref.getQuietEnd());
            out.put("quietTimezone", pref.getQuietTimezone());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Pull a 0..1439 minute value from a loose JSON body, accepting
     * either a Number ({@code 1320}) or a String ({@code "1320"}).
     * Returns null when the key is absent or the value is null.
     */
    private static Integer readMinute(java.util.Map<String, Object> body, String key) {
        if (body == null) return null;
        Object raw = body.get(key);
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { /* fall through */ }
        }
        return null;
    }

    /**
     * Set or clear the group's custom logo — Phase 4 of
     * docs/BUSINESS_MODEL.md ("co-branded page"). Admin or owner only.
     * Body: {@code {"logoImageUrl": "https://…"}}; a null / blank value
     * reverts the group to its default type emblem. The image itself is
     * uploaded separately via {@code POST /api/images}.
     */
    @PatchMapping("/{groupId}/logo")
    public ResponseEntity<Group> setLogo(@PathVariable String groupId,
                                         @RequestBody Map<String, String> body) {
        requireAdminOf(groupId);
        String url = body == null ? null : body.get("logoImageUrl");
        return ResponseEntity.ok(groupService.setLogo(groupId, url));
    }

    // ---------- Authorization helpers ----------
    //
    // All three resolve the caller's GroupRole once (constant/GroupRole)
    // and check against the formalized Owner/Admin/Member permission
    // matrix — the single source of truth shared with the FE mirror in
    // src/groups/shared/groupRoles.js.

    /** Caller must hold {@code permission} in the group. Throws 401/403/404. */
    private void requirePermission(String groupId, GroupPermission permission) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        if (!GroupRole.fromGroup(lookup(groupId), caller).has(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You don't have permission to do that in this group");
        }
    }

    /** Caller must be admin or owner of the group. Throws 401/403/404. */
    private void requireAdminOf(String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        if (!GroupRole.fromGroup(lookup(groupId), caller).isAtLeastAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Group admin or owner role required");
        }
    }

    /** Caller must be the group's owner. Throws 401/403/404. */
    private void requireOwnerOf(String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        if (GroupRole.fromGroup(lookup(groupId), caller) != GroupRole.OWNER) {
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
