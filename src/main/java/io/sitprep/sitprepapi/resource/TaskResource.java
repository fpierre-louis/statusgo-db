package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Task;
import io.sitprep.sitprepapi.domain.Task.TaskStatus;
import io.sitprep.sitprepapi.dto.TaskDto;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.service.TaskService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Task / request-for-help routes:
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
 *   POST   /api/tasks/{id}/in-progress             claimer marks active
 *   POST   /api/tasks/{id}/complete                claimer marks done
 *   POST   /api/tasks/{id}/cancel                  requester cancels
 *   POST   /api/tasks/{id}/reopen                  requester reopens cancelled
 * </pre>
 *
 * <p>Phase E enforcement on all writes — verified token email is the
 * canonical actor. Group-scope claim verifies the caller is admin/owner
 * of the claimer group.</p>
 */
@RestController
public class TaskResource {

    private final TaskService tasks;
    private final GroupService groupService;

    public TaskResource(TaskService tasks, GroupService groupService) {
        this.tasks = tasks;
        this.groupService = groupService;
    }

    // -----------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------

    @PostMapping("/api/tasks")
    public ResponseEntity<TaskDto> create(@RequestBody Task incoming) {
        String requester = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.status(HttpStatus.CREATED).body(tasks.create(incoming, requester));
    }

    // -----------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------

    @GetMapping("/api/groups/{groupId}/tasks")
    public List<TaskDto> listByGroup(
            @PathVariable String groupId,
            @RequestParam(value = "status", required = false) TaskStatus status
    ) {
        AuthUtils.requireAuthenticatedEmail();
        return tasks.listByGroup(groupId, status);
    }

    @GetMapping("/api/community/tasks")
    public List<TaskDto> discoverCommunity(
            @RequestParam("lat") Double lat,
            @RequestParam("lng") Double lng,
            @RequestParam(value = "radiusKm", required = false, defaultValue = "10") double radiusKm,
            @RequestParam(value = "status", required = false) List<TaskStatus> statuses
    ) {
        // Viewer identity feeds the follow-source merge in the service —
        // out-of-radius posts authored by emails the viewer follows
        // ride along with the radius results. Per docs/PROFILE_AND_FOLLOW.md
        // build-order step 4.
        String viewer = AuthUtils.requireAuthenticatedEmail();
        Set<TaskStatus> wanted = (statuses == null || statuses.isEmpty())
                ? EnumSet.of(TaskStatus.OPEN, TaskStatus.CLAIMED) : EnumSet.copyOf(statuses);
        return tasks.discoverCommunity(lat, lng, radiusKm, wanted, viewer);
    }

    @GetMapping("/api/me/tasks")
    public List<TaskDto> listMine(
            @RequestParam(value = "role", required = false, defaultValue = "requester") String role
    ) {
        String me = AuthUtils.requireAuthenticatedEmail();
        return "claimer".equalsIgnoreCase(role)
                ? tasks.listClaimedBy(me)
                : tasks.listRequestedBy(me);
    }

    @GetMapping("/api/tasks/{id}")
    public ResponseEntity<TaskDto> get(@PathVariable Long id) {
        AuthUtils.requireAuthenticatedEmail();
        return tasks.findById(id)
                .map(t -> ResponseEntity.ok(io.sitprep.sitprepapi.dto.TaskDto.fromEntity(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    // -----------------------------------------------------------------
    // Lifecycle (POST :action style)
    // -----------------------------------------------------------------

    @PatchMapping("/api/tasks/{id}")
    public ResponseEntity<TaskDto> patch(@PathVariable Long id, @RequestBody Task patch) {
        ensureRequester(id);
        return ResponseEntity.ok(tasks.patch(id, patch));
    }

    @DeleteMapping("/api/tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        ensureRequester(id);
        tasks.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Group leader claims a task. Body: {@code { "groupId": "...",
     * "claimerEmail": "..." (optional) }}. claimerEmail defaults to caller.
     */
    @PostMapping("/api/tasks/{id}/claim")
    public ResponseEntity<TaskDto> claim(@PathVariable Long id, @RequestBody ClaimRequest req) {
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
        return ResponseEntity.ok(tasks.claim(id, req.groupId, claimerEmail));
    }

    @PostMapping("/api/tasks/{id}/in-progress")
    public ResponseEntity<TaskDto> markInProgress(@PathVariable Long id) {
        ensureClaimer(id);
        return ResponseEntity.ok(tasks.markInProgress(id));
    }

    @PostMapping("/api/tasks/{id}/complete")
    public ResponseEntity<TaskDto> complete(@PathVariable Long id) {
        ensureClaimer(id);
        return ResponseEntity.ok(tasks.complete(id));
    }

    @PostMapping("/api/tasks/{id}/cancel")
    public ResponseEntity<TaskDto> cancel(@PathVariable Long id) {
        ensureRequester(id);
        return ResponseEntity.ok(tasks.cancel(id));
    }

    @PostMapping("/api/tasks/{id}/reopen")
    public ResponseEntity<TaskDto> reopen(@PathVariable Long id) {
        ensureRequester(id);
        return ResponseEntity.ok(tasks.reopen(id));
    }

    // -----------------------------------------------------------------
    // Authorization helpers
    // -----------------------------------------------------------------

    private void ensureRequester(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<Task> existing = tasks.findById(id);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!caller.equalsIgnoreCase(existing.get().getRequesterEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the task requester can perform this action");
        }
    }

    private void ensureClaimer(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<Task> existing = tasks.findById(id);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Task t = existing.get();
        if (t.getClaimedByEmail() == null
                || !caller.equalsIgnoreCase(t.getClaimedByEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the task claimer can perform this action");
        }
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
}
