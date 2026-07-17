package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.service.PostService;
import io.sitprep.sitprepapi.domain.TaskAssignee;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import io.sitprep.sitprepapi.web.Idempotent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Post / request-for-help routes:
 *
 * <pre>
 *   POST   /api/tasks                              create (group or community/personal)
 *   GET    /api/groups/{groupId}/tasks?status=     group-scope feed
 *   GET    /api/community/tasks?lat=&lng=&radiusKm=&status=    community-scope feed
 *   GET    /api/me/tasks?role=requester|claimer    my requests / my claims
 *   GET    /api/tasks/{id}                         single task
 *   PATCH  /api/tasks/{id}                         author-only partial update
 *   DELETE /api/tasks/{id}                         author-only
 *   POST   /api/tasks/{id}/claim                   group leader claims
 *   POST   /api/tasks/{id}/assign                  group admin assigns to a member
 *   POST   /api/tasks/{id}/in-progress             claimer/assignee marks active
 *   POST   /api/tasks/{id}/complete                claimer/assignee marks done
 *   POST   /api/tasks/{id}/cancel                  requester or group admin cancels
 *   POST   /api/tasks/{id}/reopen                  requester reopens cancelled
 * </pre>
 *
 * <p>Phase E enforcement on all writes — verified token email is the
 * canonical actor. Group-scope claim verifies the caller is admin/owner
 * of the claimer group.</p>
 */
@RestController
public class PostResource {

    private final PostService tasks;
    private final GroupService groupService;
    private final TaskAssigneeRepo assigneeRepo;

    public PostResource(PostService tasks, GroupService groupService,
                        TaskAssigneeRepo assigneeRepo) {
        this.tasks = tasks;
        this.groupService = groupService;
        this.assigneeRepo = assigneeRepo;
    }

    // -----------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------

    @PostMapping("/api/posts")
    @Idempotent
    public ResponseEntity<ApiResponse<PostDto>> create(@RequestBody Post incoming) {
        String requester = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(tasks.create(incoming, requester), ApiMeta.now()));
    }

    // -----------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------

    // Reads below are wrapped in {@link ApiResponse} per P2-3 (audit BE-02 /
    // BE-15). The FE axios interceptor in src/shared/api/http.js detects
    // the {data,error,meta} envelope and unwraps response.data to the
    // inner payload, so legacy callers keep receiving List<PostDto>
    // byte-for-byte; new consumers can read response.envelope.meta.
    @GetMapping("/api/groups/{groupId}/posts")
    public ResponseEntity<ApiResponse<List<PostDto>>> listByGroup(
            @PathVariable String groupId,
            @RequestParam(value = "status", required = false) PostStatus status
    ) {
        // Pass the viewer through so the response carries viewerThanked
        // per row — the feed UI's heart shows filled when the viewer has
        // already thanked the post.
        String viewer = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(
                tasks.listByGroup(groupId, status, viewer), ApiMeta.now()));
    }

    @GetMapping("/api/community/posts")
    public ResponseEntity<ApiResponse<List<PostDto>>> discoverCommunity(
            @RequestParam("lat") Double lat,
            @RequestParam("lng") Double lng,
            @RequestParam(value = "radiusKm", required = false, defaultValue = "10") double radiusKm,
            @RequestParam(value = "status", required = false) List<PostStatus> statuses,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        // Viewer identity feeds the follow-source merge in the service —
        // out-of-radius posts authored by emails the viewer follows
        // ride along with the radius results. Per docs/PROFILE_AND_FOLLOW.md
        // build-order step 4.
        String viewer = AuthUtils.requireAuthenticatedEmail();
        Set<PostStatus> wanted = (statuses == null || statuses.isEmpty())
                ? EnumSet.of(PostStatus.OPEN, PostStatus.CLAIMED) : EnumSet.copyOf(statuses);
        List<PostDto> page = tasks.discoverCommunity(lat, lng, radiusKm, wanted, viewer, offset, limit);
        // A full page implies there may be more — surface the next offset as
        // a header so the array body stays back-compat (older FE ignores it).
        int effLimit = limit <= 0 ? 50 : Math.min(limit, 50);
        ResponseEntity.BodyBuilder rb = ResponseEntity.ok();
        if (page.size() == effLimit) rb.header("X-Next-Cursor", String.valueOf(offset + effLimit));
        return rb.body(ApiResponse.ok(page, ApiMeta.now()));
    }

    @GetMapping("/api/agencies")
    public ResponseEntity<ApiResponse<List<PostService.AgencyDto>>> agencies(
            @RequestParam(value = "zip", required = false) String zip) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(tasks.listAgencies(zip), ApiMeta.now()));
    }

    @GetMapping("/api/community/conditions")
    public ResponseEntity<ApiResponse<PostService.ConditionsDto>> conditions(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(tasks.getConditions(lat, lng), ApiMeta.now()));
    }

    // Phase 5 Slice E — the verified agency (if any) whose jurisdiction
    // includes the viewer's current zip. Drives the community co-sign;
    // data is null when the viewer isn't in any agency's jurisdiction.
    @GetMapping("/api/community/local-agency")
    public ResponseEntity<ApiResponse<PostService.LocalAgencyDto>> localAgency() {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(tasks.localAgencyForViewer(viewer), ApiMeta.now()));
    }

    @GetMapping("/api/me/posts")
    public ResponseEntity<ApiResponse<List<PostDto>>> listMine(
            @RequestParam(value = "role", required = false, defaultValue = "requester") String role
    ) {
        String me = AuthUtils.requireAuthenticatedEmail();
        List<PostDto> result;
        if ("assignee".equalsIgnoreCase(role)) {
            result = tasks.listAssignedTo(me);
        } else if ("claimer".equalsIgnoreCase(role)) {
            result = tasks.listClaimedBy(me);
        } else {
            result = tasks.listRequestedBy(me);
        }
        return ResponseEntity.ok(ApiResponse.ok(result, ApiMeta.now()));
    }

    /**
     * Posts authored by a specific user — backs the per-business profile
     * page (/business/{email}) so a verified publisher's listings can
     * be browsed in one place. Public-equivalent: community posts are
     * visible in the feed already; this surface just collects them by
     * author. Auth still required (no anonymous reads).
     */
    @GetMapping("/api/posts/by-author/{email}")
    public ResponseEntity<ApiResponse<List<PostDto>>> listByAuthor(@PathVariable String email) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(tasks.listRequestedBy(email), ApiMeta.now()));
    }

    @GetMapping("/api/posts/{id}")
    public ResponseEntity<ApiResponse<PostDto>> get(@PathVariable Long id) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        return tasks.findDtoById(id, viewer)
                .map(dto -> ResponseEntity.ok(ApiResponse.ok(dto, ApiMeta.now())))
                .orElse(ResponseEntity.notFound().build());
    }

    // -----------------------------------------------------------------
    // Lifecycle (POST :action style)
    // -----------------------------------------------------------------

    @PatchMapping("/api/posts/{id}")
    public ResponseEntity<ApiResponse<PostDto>> patch(@PathVariable Long id, @RequestBody Post patch) {
        // Broadened 2026-07-13: work orders (kind="task") may be edited by the
        // requester, the assigned lead, OR a group admin/owner — not just the
        // author. Community posts / personal tasks stay strictly author-only.
        // The caller flows into patch so top-level imageKeys mutation (an R2-
        // destructive, delete-equivalent action) stays author-only — a
        // broadened editor may change text/triage, never wipe the photos.
        String caller = ensureCanEditTask(id);
        return ResponseEntity.ok(ApiResponse.ok(tasks.patch(id, patch, caller), ApiMeta.now()));
    }

    @DeleteMapping("/api/posts/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        ensureRequester(id);
        tasks.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Group leader claims a task. Body: {@code { "groupId": "...",
     * "claimerEmail": "..." (optional) }}. claimerEmail defaults to caller.
     */
    @PostMapping("/api/posts/{id}/claim")
    public ResponseEntity<ApiResponse<PostDto>> claim(@PathVariable Long id, @RequestBody ClaimRequest req) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        if (req == null || req.groupId == null || req.groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupId required");
        }
        // Caller must be admin or owner of the claimer group.
        Group g;
        try {
            g = groupService.getGroupByPublicId(req.groupId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
        boolean isOwner = caller.equalsIgnoreCase(g.getOwnerEmail());
        boolean isAdmin = g.getAdminEmails() != null && g.getAdminEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(caller));
        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only an admin or owner can claim on behalf of a group");
        }
        String claimerEmail = (req.claimerEmail == null || req.claimerEmail.isBlank())
                ? caller : req.claimerEmail;
        return ResponseEntity.ok(ApiResponse.ok(
                tasks.claim(id, req.groupId, claimerEmail), ApiMeta.now()));
    }

    /**
     * Add a LEAD to a work order (push assignment; Phase 2a multi-lead).
     * Body: {@code { "assigneeEmail": "..." }}. ADDITIVE — adds a lead without
     * demoting existing leads (a task may have several); the first lead
     * auto-becomes the primary point of contact. Entitled to a group Owner/Admin
     * OR any existing lead. A blank email is rejected — clearing-via-assign is
     * gone (remove with DELETE /assignees; un-lead with /demote-lead).
     */
    @PostMapping("/api/posts/{id}/assign")
    public ResponseEntity<ApiResponse<PostDto>> assign(@PathVariable Long id,
                                                      @RequestBody(required = false) AssignRequest req) {
        String caller = ensureCanAssignTask(id);
        String assignee = (req == null) ? null : req.assigneeEmail();
        if (assignee == null || assignee.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assigneeEmail required");
        }
        ensureTargetIsGroupMember(tasks.findById(id).map(Post::getGroupId).orElse(null), assignee);
        return ResponseEntity.ok(ApiResponse.ok(tasks.assign(id, assignee, caller), ApiMeta.now()));
    }

    /**
     * Add a HELPER to a work order (Step 2, multi-assignee). Body:
     * {@code { "assigneeEmail": "..." }}. Entitled to a group Owner/Admin OR the
     * task's Lead — never a Helper.
     */
    @PostMapping("/api/posts/{id}/helpers")
    public ResponseEntity<ApiResponse<PostDto>> addHelper(@PathVariable Long id,
                                                          @RequestBody(required = false) AssignRequest req) {
        String caller = ensureCanAssignTask(id);
        String email = (req == null) ? null : req.assigneeEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assigneeEmail required");
        }
        ensureTargetIsGroupMember(tasks.findById(id).map(Post::getGroupId).orElse(null), email);
        return ResponseEntity.ok(ApiResponse.ok(tasks.addHelper(id, email, caller), ApiMeta.now()));
    }

    /**
     * Demote a LEAD to HELPER (Phase 2a) — keeps them on the task; the FE cycling
     * control's Lead → Helper step. Body: {@code { "assigneeEmail": "..." }}.
     * Entitled to a group Owner/Admin OR any lead. If the demoted lead was the
     * primary, the primary clears (no auto-shuffle).
     */
    @PostMapping("/api/posts/{id}/demote-lead")
    public ResponseEntity<ApiResponse<PostDto>> demoteLead(@PathVariable Long id,
                                                           @RequestBody(required = false) AssignRequest req) {
        String caller = ensureCanAssignTask(id);
        String email = (req == null) ? null : req.assigneeEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assigneeEmail required");
        }
        return ResponseEntity.ok(ApiResponse.ok(tasks.demoteLead(id, email, caller), ApiMeta.now()));
    }

    /**
     * Set the task's PRIMARY lead / point of contact (Phase 2a). Body:
     * {@code { "assigneeEmail": "..." }} — a blank/omitted email CLEARS the primary.
     * The target must be an existing lead (409 otherwise). Entitled to a group
     * Owner/Admin OR any lead.
     */
    @PostMapping("/api/posts/{id}/primary")
    public ResponseEntity<ApiResponse<PostDto>> setPrimary(@PathVariable Long id,
                                                           @RequestBody(required = false) AssignRequest req) {
        String caller = ensureCanAssignTask(id);
        String email = (req == null) ? null : req.assigneeEmail();
        return ResponseEntity.ok(ApiResponse.ok(tasks.setPrimary(id, email, caller), ApiMeta.now()));
    }

    /**
     * Remove an assignee (LEAD or HELPER) from a work order (Step 2). Entitled to
     * a group Owner/Admin OR the task's Lead; additionally a non-manager Lead may
     * NOT remove an assignment a group Owner/Admin made. Email is a query param
     * (avoids path-encoding issues with {@code @} / dots).
     */
    @DeleteMapping("/api/posts/{id}/assignees")
    public ResponseEntity<ApiResponse<PostDto>> removeAssignee(@PathVariable Long id,
                                                               @RequestParam String email) {
        String caller = ensureCanAssignTask(id);
        String groupId = tasks.findById(id).map(Post::getGroupId).orElse(null);
        ensureLeadCannotOverrideManager(id, caller, groupId, email);
        return ResponseEntity.ok(ApiResponse.ok(tasks.removeAssignee(id, email, caller), ApiMeta.now()));
    }

    /**
     * Bundles / projects (V51) — move a task INTO a project, or OUT of it with a
     * blank/omitted projectId (detach → standalone). Body: {@code { "projectId":
     * 123 }}. The service enforces the structure rules + authorization: group
     * Owner/Admin only, the target must be a same-group project container, and a
     * project row can never be nested. Creating a project (or attaching at
     * create) goes through {@code POST /api/posts} with {@code kind="project"} /
     * a {@code projectId} in the body; closing a project reuses the container's
     * {@code /complete}. This endpoint is only the re-parent move.
     */
    @PostMapping("/api/posts/{id}/project")
    public ResponseEntity<ApiResponse<PostDto>> setProject(@PathVariable Long id,
                                                           @RequestBody(required = false) ProjectRequest req) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Long projectId = (req == null) ? null : req.projectId();
        return ResponseEntity.ok(ApiResponse.ok(tasks.moveToProject(id, projectId, caller), ApiMeta.now()));
    }

    @PostMapping("/api/posts/{id}/in-progress")
    public ResponseEntity<ApiResponse<PostDto>> markInProgress(@PathVariable Long id) {
        ensureCanProgressTask(id);
        return ResponseEntity.ok(ApiResponse.ok(tasks.markInProgress(id), ApiMeta.now()));
    }

    @PostMapping("/api/posts/{id}/complete")
    public ResponseEntity<ApiResponse<PostDto>> complete(@PathVariable Long id) {
        ensureCanProgressTask(id);
        return ResponseEntity.ok(ApiResponse.ok(tasks.complete(id), ApiMeta.now()));
    }

    @PostMapping("/api/posts/{id}/cancel")
    public ResponseEntity<ApiResponse<PostDto>> cancel(@PathVariable Long id) {
        ensureCanCancelTask(id);
        return ResponseEntity.ok(ApiResponse.ok(tasks.cancel(id), ApiMeta.now()));
    }

    // Reopen (DONE→IN_PROGRESS / CANCELLED→OPEN) + restore (ARCHIVED→OPEN) are
    // board-lifecycle moves — entitled to the same actors who progress/complete
    // a task (claimer, assignee, or group admin/owner), via ensureCanProgressTask.
    // (Reopen was author-only; broadened so an admin can reopen a task they
    // didn't personally file — consistent with complete/in-progress.)
    @PostMapping("/api/posts/{id}/reopen")
    public ResponseEntity<ApiResponse<PostDto>> reopen(@PathVariable Long id) {
        ensureCanProgressTask(id);
        return ResponseEntity.ok(ApiResponse.ok(tasks.reopen(id), ApiMeta.now()));
    }

    @PostMapping("/api/posts/{id}/restore")
    public ResponseEntity<ApiResponse<PostDto>> restore(@PathVariable Long id) {
        ensureCanProgressTask(id);
        return ResponseEntity.ok(ApiResponse.ok(tasks.restore(id), ApiMeta.now()));
    }

    /**
     * Self-serve promote — author flags their own marketplace listing
     * as sponsored for {@code days} (default 7, clamped to [1, 30]).
     * Free for v1; future monetization can gate behind a payment flow.
     *
     * <p>Body: {@code { "days": 7 }} — both fields optional; defaults
     * to 7 when omitted.</p>
     */
    @PostMapping("/api/posts/{id}/promote")
    public ResponseEntity<ApiResponse<PostDto>> promote(
            @PathVariable Long id,
            @RequestBody(required = false) PromoteRequest body) {
        String me = AuthUtils.requireAuthenticatedEmail();
        ensureRequester(id);
        int days = (body != null && body.days() != null) ? body.days() : 7;
        return ResponseEntity.ok(ApiResponse.ok(tasks.promote(id, me, days), ApiMeta.now()));
    }

    @PostMapping("/api/posts/{id}/unpromote")
    public ResponseEntity<ApiResponse<PostDto>> unpromote(@PathVariable Long id) {
        String me = AuthUtils.requireAuthenticatedEmail();
        ensureRequester(id);
        return ResponseEntity.ok(ApiResponse.ok(tasks.unpromote(id, me), ApiMeta.now()));
    }

    public record PromoteRequest(Integer days) {}

    // -----------------------------------------------------------------
    // Community redesign — confirms / save / civic status
    // -----------------------------------------------------------------

    @PostMapping("/api/posts/{id}/confirms")
    public ResponseEntity<ApiResponse<PostService.ConfirmResult>> addConfirm(@PathVariable Long id) {
        String me = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(tasks.addConfirm(id, me), ApiMeta.now()));
    }

    @DeleteMapping("/api/posts/{id}/confirms")
    public ResponseEntity<ApiResponse<PostService.ConfirmResult>> removeConfirm(@PathVariable Long id) {
        String me = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(tasks.removeConfirm(id, me), ApiMeta.now()));
    }

    @PostMapping("/api/posts/{id}/save")
    public ResponseEntity<ApiResponse<SaveResult>> save(@PathVariable Long id) {
        String me = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(new SaveResult(tasks.toggleSave(id, me, true)), ApiMeta.now()));
    }

    @DeleteMapping("/api/posts/{id}/save")
    public ResponseEntity<ApiResponse<SaveResult>> unsave(@PathVariable Long id) {
        String me = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(new SaveResult(tasks.toggleSave(id, me, false)), ApiMeta.now()));
    }

    @PatchMapping("/api/posts/{id}/civic-status")
    public ResponseEntity<ApiResponse<PostDto>> civicStatus(@PathVariable Long id,
                                                            @RequestBody CivicStatusRequest req) {
        String me = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(ApiResponse.ok(
                tasks.updateCivicStatus(id, req.status(), req.note(), me), ApiMeta.now()));
    }

    // -----------------------------------------------------------------
    // Liability release — Phase 2. Captures the waiver acceptance (or a
    // documented signing exception) so a liability-gated work order can
    // advance. Idempotent: re-POSTing the same payload is a no-op re-save.
    // -----------------------------------------------------------------

    @PostMapping("/api/posts/{id}/release")
    public ResponseEntity<ApiResponse<PostDto>> acceptRelease(@PathVariable Long id,
                                                              @RequestBody(required = false) ReleaseRequest req) {
        // Only someone entitled to sign/override for THIS task may capture it.
        ensureCanSignRelease(id);
        boolean signed = req != null && Boolean.TRUE.equals(req.releaseSigned());
        String hash = req == null ? null : req.releaseTextHash();
        String reason = req == null ? null : req.releaseExceptionReason();
        return ResponseEntity.ok(ApiResponse.ok(
                tasks.acceptRelease(id, signed, hash, reason), ApiMeta.now()));
    }

    // -----------------------------------------------------------------
    // Before/after photo evidence — Phase 2 (DOCS_EPIC_DETAIL_AND_SCALE.md).
    // Keys come from POST /api/images?scope=task (multipart → imgscalr → R2);
    // this endpoint only ever moves object KEYS — never base64, never bytes.
    // Merge semantics live in PostService.updateWorkPhotos so a photo change
    // can't clobber the triage bag or null need_type (the generic PATCH
    // replaces work_details wholesale and is author-only — wrong tool here).
    // -----------------------------------------------------------------

    /**
     * Body: {@code { "phase": "before"|"after", "addKeys": [...],
     * "removeKeys": [...] }}. RBAC per phase:
     * <ul>
     *   <li><b>before</b> — requester, assignee, or the task group's
     *       admin/owner (initial-conditions evidence);</li>
     *   <li><b>after</b> — assignee or group admin/owner ONLY (completion
     *       evidence belongs to whoever ran/supervised the work).</li>
     * </ul>
     */
    @PostMapping("/api/posts/{id}/photos")
    public ResponseEntity<ApiResponse<PostDto>> updatePhotos(@PathVariable Long id,
                                                             @RequestBody PhotoPatchRequest req) {
        if (req == null || req.phase() == null || req.phase().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phase required");
        }
        ensureCanAttachPhotos(id, req.phase());
        return ResponseEntity.ok(ApiResponse.ok(
                tasks.updateWorkPhotos(id, req.phase(), req.addKeys(), req.removeKeys()),
                ApiMeta.now()));
    }

    public record SaveResult(boolean viewerSaved) {}
    public record CivicStatusRequest(String status, String note) {}
    public record ReleaseRequest(Boolean releaseSigned, String releaseTextHash, String releaseExceptionReason) {}
    public record PhotoPatchRequest(String phase, List<String> addKeys, List<String> removeKeys) {}

    // -----------------------------------------------------------------
    // Authorization helpers
    // -----------------------------------------------------------------

    /**
     * Per-phase photo-evidence RBAC (see {@link #updatePhotos}). "before" =
     * requester | assignee | group admin/owner; "after" = assignee | group
     * admin/owner only. (The claimer is deliberately NOT entitled — the
     * mandate scopes completion evidence to admins and assigned leads;
     * widen here if field reality disagrees.) Throws 401 / 403 / 404.
     */
    private void ensureCanAttachPhotos(Long id, String phase) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<Post> existing = tasks.findById(id);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Post t = existing.get();
        boolean before = "before".equalsIgnoreCase(phase.trim());
        if (before && caller.equalsIgnoreCase(t.getRequesterEmail())) return;
        // Step 2: ANY assignee (LEAD or HELPER) may attach — read task_assignee
        // (authoritative), not the assignee_email mirror (which is now Lead-only).
        if ("task".equals(t.getKind()) && assigneeRepo.existsByPostIdAndEmailIgnoreCase(id, caller)) return;
        String groupId = t.getGroupId();
        if (groupId != null && !groupId.isBlank()) {
            try {
                Group g = groupService.getGroupByPublicId(groupId);
                boolean isOwner = caller.equalsIgnoreCase(g.getOwnerEmail());
                boolean isAdmin = g.getAdminEmails() != null && g.getAdminEmails().stream()
                        .anyMatch(e -> e != null && e.equalsIgnoreCase(caller));
                if (isOwner || isAdmin) return;
            } catch (RuntimeException ignored) {
                // group lookup failed — fall through to 403
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, before
                ? "Only the requester, assignee, or a group admin can attach before photos"
                : "Only the assignee or a group admin can attach after photos");
    }

    private void ensureRequester(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<Post> existing = tasks.findById(id);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!caller.equalsIgnoreCase(existing.get().getRequesterEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the task requester can perform this action");
        }
    }

    /**
     * Field-level edit authorization for {@code PATCH /api/posts/{id}}.
     * Broader than {@link #ensureRequester} but ONLY for group work orders:
     * <ul>
     *   <li>the requester (author) — always, for any post/kind;</li>
     *   <li>the assignee (assigned lead) — group work orders only;</li>
     *   <li>a group admin/owner of the task's own group — work orders only.</li>
     * </ul>
     * Community posts (kind != {@code task}) and personal tasks (no group)
     * stay strictly author-only, so a stranger can never edit someone's ask /
     * marketplace / tip. {@code DELETE} deliberately keeps {@link
     * #ensureRequester} (author-only) — broadening edit ≠ broadening delete.
     * Throws 401 / 403 / 404. Mirrors {@link #ensureCanProgressTask}'s role
     * resolution, plus the requester.
     */
    private String ensureCanEditTask(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<Post> existing = tasks.findById(id);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Post t = existing.get();
        // Author may always edit their own post (unchanged behavior).
        if (caller.equalsIgnoreCase(t.getRequesterEmail())) return caller;
        // Broaden ONLY for group-scoped work orders.
        String groupId = t.getGroupId();
        if ("task".equals(t.getKind()) && groupId != null && !groupId.isBlank()) {
            // Step 2: ANY assignee (LEAD or HELPER) may edit — read task_assignee
            // (authoritative), not the assignee_email mirror (now Lead-only).
            if (assigneeRepo.existsByPostIdAndEmailIgnoreCase(id, caller)) return caller;
            try {
                Group g = groupService.getGroupByPublicId(groupId);
                boolean isOwner = caller.equalsIgnoreCase(g.getOwnerEmail());
                boolean isAdmin = g.getAdminEmails() != null && g.getAdminEmails().stream()
                        .anyMatch(e -> e != null && e.equalsIgnoreCase(caller));
                if (isOwner || isAdmin) return caller;
            } catch (RuntimeException ignored) {
                // group lookup failed — fall through to 403
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only the requester, assignee, or a group admin can edit this task");
    }

    /**
     * Cancellation authorization for {@code POST /api/posts/{id}/cancel}.
     * Deliberately NARROWER than {@link #ensureCanProgressTask}: cancel is for
     * exactly two parties —
     * <ul>
     *   <li>the requester (author) — self-cancel of their own request, for any
     *       post/kind (unchanged from the old {@link #ensureRequester} gate);</li>
     *   <li>an admin/owner of the task's own group — group work orders only, so
     *       a group manager can withdraw a task on their group's board.</li>
     * </ul>
     * NOT the claimer and NOT the assignee: a volunteer working a task must not
     * be able to cancel it out from under the requester. (A task {@code Lead}
     * gains cancel rights in a later phase, once Lead is a real, checkable role
     * distinct from today's single assignee — who becomes a Helper.) Community
     * posts (kind != {@code task}) and personal tasks (no group) stay strictly
     * author-only; {@code DELETE} is unaffected (still author-only). Throws
     * 401 / 403 / 404. Reuses {@link #ensureCanProgressTask}'s group-owner/admin
     * resolution, minus the claimer/assignee branch.
     */
    private void ensureCanCancelTask(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<Post> existing = tasks.findById(id);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Post t = existing.get();
        // Requester may always cancel their own request (unchanged behavior).
        if (caller.equalsIgnoreCase(t.getRequesterEmail())) return;
        // Broaden ONLY for group-scoped work orders — admin/owner OR the task's
        // Lead (Step 2). NEVER a Helper: a volunteer working a task can't cancel
        // it out from under the requester.
        String groupId = t.getGroupId();
        if ("task".equals(t.getKind()) && groupId != null && !groupId.isBlank()) {
            if (assigneeRepo.existsByPostIdAndEmailIgnoreCaseAndRole(id, caller, TaskAssignee.Role.LEAD)) {
                return; // the task's Lead
            }
            try {
                Group g = groupService.getGroupByPublicId(groupId);
                boolean isOwner = caller.equalsIgnoreCase(g.getOwnerEmail());
                boolean isAdmin = g.getAdminEmails() != null && g.getAdminEmails().stream()
                        .anyMatch(e -> e != null && e.equalsIgnoreCase(caller));
                if (isOwner || isAdmin) return;
            } catch (RuntimeException ignored) {
                // group lookup failed — fall through to 403
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only the task requester, its Lead, or a group admin can cancel this task");
    }

    /**
     * Caller must be entitled to drive a task's work lifecycle
     * (in-progress / complete). Three entitled roles:
     * <ul>
     *   <li>the claimer — community pull flow ({@code claimedByEmail});</li>
     *   <li>the assignee — group push flow ({@code assigneeEmail}), so a
     *       member an admin assigned a work-order to can progress and
     *       close it without first "claiming" it;</li>
     *   <li>an admin/owner of the task's group — so a group manager can
     *       close out work on their group's tasks.</li>
     * </ul>
     * Throws 401 / 403 / 404.
     */
    private void ensureCanProgressTask(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<Post> existing = tasks.findById(id);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Post t = existing.get();
        if (caller.equalsIgnoreCase(t.getClaimedByEmail())) return;
        // Step 2: ANY assignee (LEAD or HELPER) may progress — Helpers do the
        // work. Reads task_assignee (authoritative), not the assigneeEmail mirror.
        if ("task".equals(t.getKind())
                && assigneeRepo.existsByPostIdAndEmailIgnoreCase(id, caller)) {
            return;
        }
        String groupId = t.getGroupId();
        if (groupId != null && !groupId.isBlank()) {
            try {
                Group g = groupService.getGroupByPublicId(groupId);
                boolean isOwner = caller.equalsIgnoreCase(g.getOwnerEmail());
                boolean isAdmin = g.getAdminEmails() != null && g.getAdminEmails().stream()
                        .anyMatch(e -> e != null && e.equalsIgnoreCase(caller));
                if (isOwner || isAdmin) return;
            } catch (RuntimeException ignored) {
                // group lookup failed — fall through to 403
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only the task claimer, an assignee, or a group admin can update it");
    }

    /**
     * Group-manager check — caller is Owner or Admin of {@code groupId}.
     * Null-safe + group-lookup-safe (a failed lookup ⇒ "not a manager"). Shared
     * by the Step-2 assignment guards.
     */
    private boolean isGroupManagerOf(String groupId, String caller) {
        if (groupId == null || groupId.isBlank() || caller == null) return false;
        try {
            Group g = groupService.getGroupByPublicId(groupId);
            if (caller.equalsIgnoreCase(g.getOwnerEmail())) return true;
            return g.getAdminEmails() != null && g.getAdminEmails().stream()
                    .anyMatch(e -> e != null && e.equalsIgnoreCase(caller));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Assignment authorization (Step 2 — {@code /assign} + {@code /helpers}): a
     * group {@code Owner/Admin} OR the task's {@code Lead}. A {@code Helper} may
     * NEVER manage assignments. Group work orders only. Returns the verified
     * caller. Throws 401 / 403 / 404.
     */
    private String ensureCanAssignTask(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<Post> existing = tasks.findById(id);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Post t = existing.get();
        String groupId = t.getGroupId();
        if (!"task".equals(t.getKind()) || groupId == null || groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Assignment is only available on group work orders");
        }
        if (isGroupManagerOf(groupId, caller)) return caller;
        if (assigneeRepo.existsByPostIdAndEmailIgnoreCaseAndRole(id, caller, TaskAssignee.Role.LEAD)) {
            return caller; // the task's Lead
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only a group admin/owner or the task's Lead can manage assignments");
    }

    /**
     * Extra rule when REMOVING an assignee (Step 2): a Lead who is NOT a group
     * Owner/Admin may not remove an assignment that a group Owner/Admin made — a
     * Lead can add/manage Helpers but can't override the group's manager.
     * Owner/Admins are unrestricted. Call AFTER {@link #ensureCanAssignTask}.
     *
     * <p>KNOWN LIMITATION: provenance is re-resolved against the assigner's
     * CURRENT group role, so if the admin who made an assignment later loses
     * admin status a Lead could then remove that row. A durable fix needs a
     * persisted is-manager-origin flag on {@code task_assignee} (future migration).</p>
     */
    private void ensureLeadCannotOverrideManager(Long id, String caller, String groupId, String targetEmail) {
        if (isGroupManagerOf(groupId, caller)) return; // managers unrestricted
        assigneeRepo.findByPostIdAndEmailIgnoreCase(id, targetEmail).ifPresent(row -> {
            if (isGroupManagerOf(groupId, row.getAssignedBy())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "A Lead can't remove an assignment made by a group admin/owner");
            }
        });
    }

    /**
     * Step 2: an assignment TARGET must be a member (or Owner/Admin) of the
     * task's group. Without this a non-manager Lead could add an arbitrary
     * outside email, who would then gain progress/edit rights on the group's
     * work order (and, via promotion, cancel/assign). Blank target = clearing,
     * no check. Throws 403 (non-member) / 404 (group gone).
     */
    private void ensureTargetIsGroupMember(String groupId, String targetEmail) {
        if (targetEmail == null || targetEmail.isBlank()) return;
        Group g;
        try {
            g = groupService.getGroupByPublicId(groupId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
        String want = targetEmail.trim();
        boolean member = want.equalsIgnoreCase(g.getOwnerEmail())
                || (g.getAdminEmails() != null && g.getAdminEmails().stream()
                        .anyMatch(e -> e != null && e.equalsIgnoreCase(want)))
                || (g.getMemberEmails() != null && g.getMemberEmails().stream()
                        .anyMatch(e -> e != null && e.equalsIgnoreCase(want)));
        if (!member) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Can only assign a member of the task's group");
        }
    }

    /**
     * Caller must be entitled to capture the liability release for a task.
     * Entitled roles: the requester (the property owner / person the work is
     * for), the claimer, the assignee, or an admin/owner of the task's group —
     * i.e. anyone who could legitimately witness the release or sign on the
     * requester's behalf in the field. Throws 401 / 403 / 404.
     */
    private String ensureCanSignRelease(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<Post> existing = tasks.findById(id);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Post t = existing.get();
        if (caller.equalsIgnoreCase(t.getRequesterEmail())
                || caller.equalsIgnoreCase(t.getClaimedByEmail())) {
            return caller;
        }
        // Step 2: ANY assignee (LEAD or HELPER) may witness the release — read
        // task_assignee (authoritative), not the assignee_email mirror.
        if ("task".equals(t.getKind()) && assigneeRepo.existsByPostIdAndEmailIgnoreCase(id, caller)) {
            return caller;
        }
        String groupId = t.getGroupId();
        if (groupId != null && !groupId.isBlank()) {
            try {
                Group g = groupService.getGroupByPublicId(groupId);
                boolean isOwner = caller.equalsIgnoreCase(g.getOwnerEmail());
                boolean isAdmin = g.getAdminEmails() != null && g.getAdminEmails().stream()
                        .anyMatch(e -> e != null && e.equalsIgnoreCase(caller));
                if (isOwner || isAdmin) return caller;
            } catch (RuntimeException ignored) {
                // group lookup failed — fall through to 403
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only the requester, claimer, assignee, or a group admin can capture the liability release");
    }

    /**
     * Caller must be admin or owner of the task's own group — the
     * authorization for group-admin actions like assignment. Returns
     * the verified caller email. Throws 401 / 403 / 404.
     */
    private String ensureGroupManagerOf(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<Post> existing = tasks.findById(id);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String groupId = existing.get().getGroupId();
        if (groupId == null || groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Assignment is only available on group tasks");
        }
        Group g;
        try {
            g = groupService.getGroupByPublicId(groupId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
        boolean isOwner = caller.equalsIgnoreCase(g.getOwnerEmail());
        boolean isAdmin = g.getAdminEmails() != null && g.getAdminEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(caller));
        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a group admin or owner can assign tasks");
        }
        return caller;
    }

    // -----------------------------------------------------------------
    // Exception mapping
    // -----------------------------------------------------------------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    public record ClaimRequest(String groupId, String claimerEmail) {}

    public record AssignRequest(String assigneeEmail) {}

    /** Bundles / projects (V51) — body for POST /{id}/project (null projectId detaches). */
    public record ProjectRequest(Long projectId) {}
}
