package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.TaskCommentDto;
import io.sitprep.sitprepapi.service.TaskCommentService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Comments on tasks (community-feed posts). Mirrors {@code CommentResource}
 * exactly modulo the path prefix:
 *
 * <pre>
 *   POST   /api/tasks/{taskId}/comments              create
 *   GET    /api/tasks/{taskId}/comments              list (oldest -> newest)
 *   GET    /api/tasks/{taskId}/comments/since?sinceIso=...  delta backfill
 *   PUT    /api/task-comments/{id}                   author-only edit
 *   DELETE /api/task-comments/{id}                   author-only delete
 * </pre>
 *
 * <p>The split between {@code /api/tasks/{taskId}/comments} (collection
 * + create) and {@code /api/task-comments/{id}} (single-comment edit /
 * delete) matches how PostComments carved its surface — collection ops
 * are nested under their parent for clarity; single-row ops are flat so
 * the FE doesn't need to thread the parent id through every edit/delete
 * call.</p>
 *
 * <p>Phase E enforcement on every WRITE — anonymous users cannot post or
 * edit. Author is always the verified token email; PUT and DELETE
 * additionally verify the caller authored the target comment.</p>
 */
@RestController
public class TaskCommentResource {

    private final TaskCommentService service;

    public TaskCommentResource(TaskCommentService service) {
        this.service = service;
    }

    // -----------------------------------------------------------------
    // Collection ops (nested under parent task)
    // -----------------------------------------------------------------

    @PostMapping("/api/tasks/{taskId}/comments")
    public ResponseEntity<TaskCommentDto> createComment(
            @PathVariable Long taskId,
            @RequestBody TaskCommentDto dto) {
        String author = AuthUtils.requireAuthenticatedEmail();
        // Always trust the token over the body — body author is informational.
        dto.setAuthor(author);
        dto.setTaskId(taskId);
        TaskCommentDto saved = service.createCommentFromDto(dto);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/api/tasks/{taskId}/comments")
    public ResponseEntity<List<TaskCommentDto>> getCommentsByTaskId(@PathVariable Long taskId) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.getCommentsByTaskId(taskId));
    }

    @GetMapping("/api/tasks/{taskId}/comments/since")
    public List<TaskCommentDto> getCommentsSince(
            @PathVariable Long taskId,
            @RequestParam String sinceIso) {
        AuthUtils.requireAuthenticatedEmail();
        return service.getCommentsSince(taskId, Instant.parse(sinceIso));
    }

    // -----------------------------------------------------------------
    // Single-comment ops (flat, mirrors /api/comments/{id})
    // -----------------------------------------------------------------

    @PutMapping("/api/task-comments/{id}")
    public ResponseEntity<TaskCommentDto> updateComment(
            @PathVariable Long id,
            @RequestBody TaskCommentDto dto) {
        ensureAuthors(id);
        dto.setId(id);
        TaskCommentDto updated = service.updateCommentFromDto(dto);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/api/task-comments/{id}")
    public ResponseEntity<Void> deleteComment(@PathVariable Long id) {
        ensureAuthors(id);
        service.deleteCommentByIdAndBroadcast(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 404 if the comment doesn't exist (don't leak ids), 403 if the caller
     * isn't its author. Matches the semantics of {@code CommentResource}'s
     * equivalent helper.
     */
    private void ensureAuthors(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<TaskCommentDto> existing = service.getCommentById(id);
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String author = existing.get().getAuthor();
        if (author == null || !author.equalsIgnoreCase(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Comment belongs to a different user");
        }
    }
}
