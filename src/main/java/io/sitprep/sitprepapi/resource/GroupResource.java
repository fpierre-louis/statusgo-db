package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.CheckInRollupDto;
import io.sitprep.sitprepapi.dto.EmailRequest;
import io.sitprep.sitprepapi.dto.NudgeResultDto;
import io.sitprep.sitprepapi.dto.GroupMembershipActionResultDto;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private io.sitprep.sitprepapi.service.GroupOwnerContactService groupOwnerContactService;

    @Autowired
    private io.sitprep.sitprepapi.service.GroupLeaveService groupLeaveService;

    @Autowired
    private io.sitprep.sitprepapi.service.GroupPostReadReceiptService groupPostReadReceiptService;

    @Autowired
    private io.sitprep.sitprepapi.service.GroupMuteService groupMuteService;

    /**
     * The Spring-managed mapper, deliberately — the same instance the HTTP
     * message converter uses, so a scoped read is byte-for-byte the old entity
     * response minus the removed keys.
     */
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @GetMapping("/admin")
    public List<Group> getGroupsByAdminEmail() {
        AuthUtils.requireAuthenticatedEmail();
        return groupService.getGroupsForCurrentAdmin();
    }

    /**
     * Create a group. The body is a {@link io.sitprep.sitprepapi.dto.CreateGroupRequest}:
     * the server mints the id and code, the caller is the whole roster, the
     * tier is FREE and no check-in runs. Extra keys (the frontend still sends
     * a whole group) are ignored. 409 when the body names an existing id.
     * See {@code GroupService#createGroup(CreateGroupRequest, String)}.
     *
     * <p>{@code @Idempotent}: the server mints the id, so a replay of the same
     * body would be a second group. The FE sends create through the offline
     * outbox, which replays with one stable {@code Idempotency-Key} after a
     * timeout or 5xx (possibly after the first attempt committed); the key is
     * the only thing that makes that replay return the first group.</p>
     */
    @PostMapping
    @io.sitprep.sitprepapi.web.Idempotent
    public Group createGroup(@RequestBody io.sitprep.sitprepapi.dto.CreateGroupRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return groupService.createGroup(body, caller);
    }

    @PutMapping("/{groupId}")
    public ResponseEntity<Group> updateGroup(@PathVariable String groupId, @RequestBody Group groupDetails) {
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.updateGroupByPublicId(groupId, groupDetails));
    }

    /**
     * Set the group's alert, and nothing else.
     *
     * <p>Same gate as the full update it replaces ({@code requireAdminOf}), but
     * a payload of one boolean. The frontend previously had to
     * {@code PUT /groups/{id}} with an entire group object — assembled from a
     * snapshot taken when the page mounted — just to flip this flag, which
     * meant every alert toggle silently reverted whatever another admin had
     * changed since. See {@code GroupService#setAlert}.</p>
     */
    @PostMapping("/{groupId}/alert")
    public ResponseEntity<Group> setAlert(
            @PathVariable String groupId,
            @RequestBody SetAlertRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.setAlert(groupId, body.active(), caller));
    }

    /** Body of {@link #setAlert}. One field, deliberately. */
    public record SetAlertRequest(boolean active) {}

    /**
     * Keep the running check-in going for another window without restarting
     * it — answers so far still count (ask-to-check-in plan C-4). Organizers
     * only. 409 when no check-in is running.
     */
    @PostMapping("/{groupId}/alert/continue")
    public ResponseEntity<ContinueCheckInDto> continueCheckIn(@PathVariable String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireAdminOf(groupId);
        try {
            return ResponseEntity.ok(new ContinueCheckInDto(groupService.continueCheckIn(groupId, caller)));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** When the continued check-in now ends by itself. */
    public record ContinueCheckInDto(java.time.Instant alertExpiresAt) {}

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

    /**
     * Fields on {@link Group} that only someone with a relationship to the group
     * may read.
     *
     * <p>Until 2026-08-24 this route returned the raw entity to any signed-in
     * caller. <b>Households are Group rows</b>, so for a household id that meant
     * the family roster plus the home address and its coordinate, to anyone with
     * an account. It also handed out the group code, which is how you join.</p>
     *
     * <p>Stripped rather than 403'd, because the answer for a non-member already
     * exists and is public: {@code /api/groups/{id}/preview} deliberately serves
     * exactly this reduced view to invitees who are not signed in. Refusing here
     * would break the roughly twenty frontend call sites that use this route
     * before anyone could check which of them are member-only surfaces; scoping
     * the payload removes the leak and can break nothing that was not reading a
     * roster it had no claim to.</p>
     */
    private static final java.util.Set<String> RELATIONSHIP_ONLY_FIELDS = java.util.Set.of(
            // The roster. This is the finding.
            "memberEmails", "adminEmails", "pendingMemberEmails", "ownerEmail",
            // A household's home. `latitude`/`longitude` are the delegating
            // accessors over the embedded homeLocation, not fields, so they need
            // naming here too or they stay in the JSON.
            "address", "latitude", "longitude", "zipCode",
            // The join code.
            "groupCode",
            // Billing identity and the agency contact of record.
            "stripeCustomerId", "stripeSubscriptionId", "subscriptionStatus",
            "subscriptionOverrideTier", "officialContactEmail",
            // The resolved tier names an active override tier, and the plan
            // badge is a member surface (B1 review).
            "effectivePlanTier", "planUnlocks"
    );

    @GetMapping("/{groupId}")
    public com.fasterxml.jackson.databind.JsonNode getGroupById(@PathVariable String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Group group = groupService.getGroupByPublicId(groupId);
        com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.valueToTree(group);
        node.put("jurisdictionReady",
                io.sitprep.sitprepapi.service.AgencyAuthorizationService.isJurisdictionReady(group));
        node.set("viewerAgencyPermissions", objectMapper.valueToTree(
                io.sitprep.sitprepapi.service.AgencyAuthorizationService.viewerPermissions(group, caller)));
        // `planTier` is the Stripe-synced base; this is what the group really
        // has (an unexpired platform override wins). PR-01.
        io.sitprep.sitprepapi.constant.PlanTier effective =
                io.sitprep.sitprepapi.service.BillingService.effectivePlanTier(group);
        node.put("effectivePlanTier", effective.name());
        node.set("planUnlocks", objectMapper.valueToTree(effective.unlocks()));
        if (!hasRelationshipTo(group, caller)) {
            RELATIONSHIP_ONLY_FIELDS.forEach(node::remove);
        }
        return node;
    }

    /**
     * Owner, admin, member — or pending.
     *
     * <p>Pending is included deliberately, and is the part that is easy to get
     * wrong: {@link GroupRole#fromGroup} resolves a pending request to
     * {@code NONE}, so relying on it alone would strip the pending list from the
     * one person who needs it, and the frontend's {@code roleOf} would render
     * "Join" to someone whose request is already in.</p>
     */
    private boolean hasRelationshipTo(Group group, String caller) {
        if (GroupRole.fromGroup(group, caller) != GroupRole.NONE) return true;
        return containsCaseInsensitive(group == null ? null : group.getPendingMemberEmails(), caller);
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
    /**
     * The group owner's phone, for members of the group or of a linked parent
     * group ({@link io.sitprep.sitprepapi.service.GroupOwnerContactService}).
     * 404 when the group is unknown or the caller may not see it — the same
     * answer for both. {@code phone} is null when none is on file.
     */
    @GetMapping("/{groupId}/owner-contact")
    public ResponseEntity<io.sitprep.sitprepapi.service.GroupOwnerContactService.OwnerContact> getOwnerContact(
            @PathVariable String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return groupOwnerContactService.ownerContact(groupId, caller)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{groupId}/preview")
    public ResponseEntity<io.sitprep.sitprepapi.dto.GroupPreviewDto> getGroupPreview(
            @PathVariable String groupId
    ) {
        String viewer = AuthUtils.getCurrentUserEmail();
        // Through notFoundIfMissing, the same conversion lookup() uses.
        //
        // Until 2026-08-24 this called the service directly, so an unknown id
        // escaped as GroupService's RuntimeException and rendered a 500 with a
        // stack trace. lookup() had converted that to a 404 since it was
        // written, explicitly "so we don't leak whether the id is taken" — and
        // this is the one route where that mattered most: it is PUBLIC, it is
        // the endpoint invite recipients and link crawlers hit, and a 500-vs-200
        // split answers the question the 404 exists to refuse.
        //
        // The rule was written once and never reached its second reader. Sharing
        // the helper is the fix; calling lookup() here as well would have worked
        // and cost a second query on the one route bots hit hardest.
        return ResponseEntity.ok(
                notFoundIfMissing(() -> groupService.getGroupPreview(groupId, viewer)));
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
     *
     * <p>Households only: <b>429</b> while another ask from this household is
     * inside its 10-minute cooldown, with {@code Retry-After} (seconds) and a
     * body naming that ask — {@code {requestedBy:{name,email}, requestedAt}} —
     * so the client says who asked rather than showing an error. Org groups
     * never see a 429 here.</p>
     */
    @PostMapping("/{groupId}/check-in-request")
    public ResponseEntity<?> requestCheckIn(@PathVariable String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        try {
            GroupService.AskEveryoneResult result = groupService.requestCheckIn(groupId, caller);
            if (!result.sent()) {
                long retrySeconds = result.retryAt() == null ? 0
                        : Math.max(1, java.time.Duration.between(java.time.Instant.now(), result.retryAt()).toSeconds());
                Map<String, Object> requestedBy = new LinkedHashMap<>();
                requestedBy.put("name", result.requestedByName());
                requestedBy.put("email", result.requestedByEmail());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("requestedBy", requestedBy);
                body.put("requestedAt", result.requestedAt());
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(retrySeconds))
                        .body(body);
            }
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

    @GetMapping("/{groupId}/check-in-rollup")
    public ResponseEntity<CheckInRollupDto> getCheckInRollup(@PathVariable String groupId) {
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.getCheckInRollup(groupId));
    }

    @PostMapping("/{groupId}/check-in-ping-missing")
    public ResponseEntity<CheckInRollupDto> pingMissingCheckIns(@PathVariable String groupId) {
        requireAdminOf(groupId);
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(groupService.pingMissingCheckIns(groupId, caller));
    }

    /**
     * Nudge ONE member of a household to check in — the person sheet's
     * "Nudge" button. Deliberately NOT admin-gated: inside a household any
     * member may ask another to say they are safe, which is the same rule
     * {@code requestCheckIn} already applies. Membership on both sides is
     * enforced in the service.
     *
     * <p>Returns <b>429</b> when the nudge is still cooling down. That is a
     * real answer rather than an error — the caller shows "Already nudged"
     * instead of retrying, and the cooldown is what keeps the button from
     * being holdable. (An earlier note here said the nudge bypasses quiet
     * hours. It does not: {@code CHECK_IN_REQUEST} is not on
     * {@code PushPolicyService.CRITICAL_BYPASS}, so a nudge inside the
     * recipient's quiet window is demoted to the inbox like any other Lane A
     * push. Corrected 2026-09-11 during the P0-B audit.)</p>
     *
     * <p>The 200 body reports whether the push was sent SILENTLY — P0-B, see
     * {@code ConcealmentSafetyService}. The sender learns what the transport
     * did; never that it was delivered.</p>
     */
    @PostMapping("/{groupId}/nudge")
    public ResponseEntity<NudgeResultDto> nudgeMember(@PathVariable String groupId,
                                            @RequestBody EmailRequest req) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        try {
            GroupService.NudgeResult result = groupService.nudgeMember(groupId, caller, req.email());
            return result.sent()
                    ? ResponseEntity.ok(new NudgeResultDto(true, result.silent()))
                    : ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        } catch (SecurityException se) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, se.getMessage());
        }
    }

    /**
     * The caller leaves the group. 204; 409 for the owner (transfer or delete
     * first); 404 when the group is unknown or the caller is not in it. See
     * {@link io.sitprep.sitprepapi.service.GroupLeaveService}.
     */
    @PostMapping("/{groupId}/leave")
    public ResponseEntity<Void> leave(@PathVariable String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        groupLeaveService.leave(groupId, caller);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{groupId}/members/approve")
    public ResponseEntity<GroupMembershipActionResultDto> approveMember(@PathVariable String groupId, @RequestBody EmailRequest req) {
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.approveMemberAction(groupId, req.email()));
    }

    @PostMapping("/{groupId}/members/reject")
    public ResponseEntity<GroupMembershipActionResultDto> rejectPending(@PathVariable String groupId, @RequestBody EmailRequest req) {
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.rejectPendingMemberAction(groupId, req.email()));
    }

    /** 409 for the owner; another admin takes the owner (403). See GroupService. */
    @PostMapping("/{groupId}/members/remove")
    public ResponseEntity<Group> removeMember(@PathVariable String groupId, @RequestBody EmailRequest req) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.removeMember(groupId, req.email(), caller));
    }

    /** Promotes an existing member only; anyone else is a 409 (D2). */
    @PostMapping("/{groupId}/admins/add")
    public ResponseEntity<Group> addAdmin(@PathVariable String groupId, @RequestBody EmailRequest req) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.promoteMemberToAdmin(groupId, req.email(), caller));
    }

    /** Demoting another admin takes the owner (403); the owner can't be demoted (403). */
    @PostMapping("/{groupId}/admins/remove")
    public ResponseEntity<Group> removeAdmin(@PathVariable String groupId, @RequestBody EmailRequest req) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireAdminOf(groupId);
        return ResponseEntity.ok(groupService.removeAdmin(groupId, req.email(), caller));
    }

    /** To an existing member only (409 otherwise), who is notified. Owner only. */
    @PostMapping("/{groupId}/owner/transfer")
    public ResponseEntity<Group> transferOwner(@PathVariable String groupId, @RequestBody EmailRequest req) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        requireOwnerOf(groupId);
        return ResponseEntity.ok(groupService.transferOwnershipToMember(groupId, req.email(), caller));
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
        groupPostReadReceiptService.markGroupRead(groupId, email);
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

    /**
     * Set or clear the circle's cover image. Admin or owner only.
     * Body: {@code {"coverImageUrl": "https://…"}}; null / blank reverts the
     * hero to the group type's default gradient. Uploaded images ride the
     * shared {@code POST /api/images} pipe first, exactly like the logo.
     */
    @PatchMapping("/{groupId}/cover")
    public ResponseEntity<Group> setCover(@PathVariable String groupId,
                                          @RequestBody Map<String, String> body) {
        requireAdminOf(groupId);
        String url = body == null ? null : body.get("coverImageUrl");
        return ResponseEntity.ok(groupService.setCover(groupId, url));
    }

    /**
     * Rename the group, and nothing else. Admin or owner only (the same gate as
     * the whole-group PUT). Body: {@code {"groupName": "…"}}; trimmed, 400 when
     * blank or wider than the column. See {@code GroupService#renameGroup}.
     */
    @PatchMapping("/{groupId}/name")
    public ResponseEntity<Group> renameGroup(@PathVariable String groupId,
                                             @RequestBody Map<String, String> body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        String name = body == null ? null : body.get("groupName");
        return ResponseEntity.ok(notFoundIfMissing(() -> groupService.renameGroup(groupId, name, caller)));
    }

    /**
     * Link {@code childGroupId} as a subgroup of {@code parentGroupId}: both
     * sides written in one transaction. Caller must be admin or owner of BOTH.
     * Idempotent. Returns the two id lists as stored. See
     * {@code GroupService#linkSubGroup}.
     */
    @PutMapping("/{parentGroupId}/subgroups/{childGroupId}")
    public ResponseEntity<io.sitprep.sitprepapi.dto.SubGroupLinkDto> linkSubGroup(
            @PathVariable String parentGroupId, @PathVariable String childGroupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(notFoundIfMissing(
                () -> groupService.linkSubGroup(parentGroupId, childGroupId, caller)));
    }

    /** Undo {@link #linkSubGroup}. Same gate, same response, idempotent. */
    @DeleteMapping("/{parentGroupId}/subgroups/{childGroupId}")
    public ResponseEntity<io.sitprep.sitprepapi.dto.SubGroupLinkDto> unlinkSubGroup(
            @PathVariable String parentGroupId, @PathVariable String childGroupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(notFoundIfMissing(
                () -> groupService.unlinkSubGroup(parentGroupId, childGroupId, caller)));
    }

    // ---------- Authorization helpers ----------
    //
    // Both resolve the caller's GroupRole once (constant/GroupRole)
    // and check against the formalized Owner/Admin/Member permission
    // matrix — the single source of truth shared with the FE mirror in
    // src/groups/shared/groupRoles.js.

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
        return notFoundIfMissing(() -> groupService.getGroupByPublicId(groupId));
    }

    /**
     * Run a group lookup and turn "no such group" into a 404.
     *
     * <p>GroupService signals a missing group with a bare
     * {@code RuntimeException("Group not found...")}, which Spring renders as a
     * 500. Converting it to a 404 is deliberate: <b>it keeps the response from
     * confirming whether an id is taken.</b> A 500 for one id and a 200 for
     * another answers exactly the question a 404 refuses.</p>
     *
     * <p>Extracted from {@code lookup()} so the rule has one home. It had two
     * readers and only one of them knew about it — {@code /preview} called the
     * service directly and leaked 500s on a public route for as long as it
     * existed.</p>
     *
     * <p>A {@link ResponseStatusException} passes through untouched. Without
     * that, a considered 403 from deeper in the stack would be laundered into a
     * 404 by the catch below — turning a gate's answer into a lie about
     * existence.</p>
     */
    private <T> T notFoundIfMissing(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
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
}
