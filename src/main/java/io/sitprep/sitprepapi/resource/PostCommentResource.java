package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.PostCommentDto;
import io.sitprep.sitprepapi.service.PostCommentService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Comments on tasks (community-feed posts). Mirrors {@code GroupPostCommentResource}
 * exactly modulo the path prefix:
 *
 * <pre>
 *   POST   /api/tasks/{postId}/comments              create
 *   GET    /api/tasks/{postId}/comments              list (oldest -> newest)
 *   GET    /api/tasks/{postId}/comments/since?sinceIso=...  delta backfill
 *   PUT    /api/task-comments/{id}                   author-only edit
 *   DELETE /api/task-comments/{id}                   author-only delete
 * </pre>
 *
 * <p>The split between {@code /api/tasks/{postId}/comments} (collection
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
public class PostCommentResource {

    private final PostCommentService service;

    public PostCommentResource(PostCommentService service) {
        this.service = service;
    }

    // -----------------------------------------------------------------
    // Collection ops (nested under parent task)
    // -----------------------------------------------------------------

    @PostMapping("/api/posts/{postId}/comments")
    public ResponseEntity<PostCommentDto> createComment(
            @PathVariable Long postId,
            @RequestBody PostCommentDto dto) {
        String author = AuthUtils.requireAuthenticatedEmail();
        // Always trust the token over the body — body author is informational.
        dto.setAuthor(author);
        dto.setPostId(postId);
        PostCommentDto saved = service.createCommentFromDto(dto);
        return ResponseEntity.ok(saved);
    }

    /**
     * Comment thread fetch. Cursor-paginated when either query param is
     * supplied; falls back to full-thread (legacy) when both are absent.
     *
     * <ul>
     *   <li>{@code limit} — page size (1..100, default 30)</li>
     *   <li>{@code beforeId} — return comments older than this id; omit
     *       for the most-recent page</li>
     * </ul>
     *
     * <p>Returns chronological order within the page so the FE can
     * prepend incoming pages above its existing list when scrolling up.</p>
     */
    @GetMapping("/api/posts/{postId}/comments")
    public ResponseEntity<List<PostCommentDto>> getCommentsByPostId(
            @PathVariable Long postId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "beforeId", required = false) Long beforeId) {
        // Pass viewer through so the response carries viewerThanked per row
        // — the comment thread UI's heart shows filled when the viewer has
        // already reacted on a given comment.
        String viewer = AuthUtils.requireAuthenticatedEmail();
        // When pagination params are supplied, use the cursor path; else
        // fall back to legacy full-thread fetch (back-compat for any
        // caller still expecting the unbounded list).
        if (limit != null || beforeId != null) {
            int safeLimit = (limit == null) ? 30 : limit;
            return ResponseEntity.ok(service.getCommentsPage(postId, viewer, beforeId, safeLimit));
        }
        return ResponseEntity.ok(service.getCommentsByPostId(postId, viewer));
    }

    @GetMapping("/api/posts/{postId}/comments/since")
    public List<PostCommentDto> getCommentsSince(
            @PathVariable Long postId,
            @RequestParam String sinceIso) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        return service.getCommentsSince(postId, Instant.parse(sinceIso), viewer);
    }

    // -----------------------------------------------------------------
    // Single-comment ops (flat, mirrors /api/comments/{id})
    // -----------------------------------------------------------------

    @PutMapping("/api/post-comments/{id}")
    public ResponseEntity<PostCommentDto> updateComment(
            @PathVariable Long id,
            @RequestBody PostCommentDto dto) {
        ensureAuthors(id);
        dto.setId(id);
        PostCommentDto updated = service.updateCommentFromDto(dto);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/api/post-comments/{id}")
    public ResponseEntity<Void> deleteComment(@PathVariable Long id) {
        ensureAuthors(id);
        service.deleteCommentByIdAndBroadcast(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 404 if the comment doesn't exist (don't leak ids), 403 if the caller
     * isn't its author. Matches the semantics of {@code GroupPostCommentResource}'s
     * equivalent helper.
     */
    private void ensureAuthors(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<PostCommentDto> existing = service.getCommentById(id);
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
