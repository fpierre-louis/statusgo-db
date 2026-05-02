package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.PostReactionDto;
import io.sitprep.sitprepapi.service.PostReactionService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Emoji reactions on tasks (community-feed posts). Mirrors
 * {@code GroupPostReactionResource} so the eventual GroupPost/Post entity merge
 * collapses both surfaces with no client-side change beyond a path swap.
 *
 * <p>The viewer is always the verified Firebase token email — clients
 * can't react on someone else's behalf.</p>
 *
 * <pre>
 *   POST   /api/tasks/{id}/reactions { emoji }   -> add (idempotent)
 *   DELETE /api/tasks/{id}/reactions/{emoji}     -> remove
 *   GET    /api/tasks/{id}/reactions             -> roster lookup
 * </pre>
 *
 * <p>The roster shape ({@code Map<String, List<PostReactionDto>>}) is
 * deliberately reused from GroupPostReaction so FE consumers can share the
 * same render code regardless of source surface.</p>
 */
@RestController
@RequestMapping("/api/tasks/{taskId}/reactions")
public class PostReactionResource {

    private final PostReactionService service;

    public PostReactionResource(PostReactionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, List<PostReactionDto>>> add(
            @PathVariable Long taskId,
            @RequestBody AddReactionRequest body) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        Map<String, List<PostReactionDto>> reactions =
                service.add(taskId, actor, body == null ? null : body.emoji());
        return ResponseEntity.ok(reactions);
    }

    @DeleteMapping("/{emoji}")
    public ResponseEntity<Map<String, List<PostReactionDto>>> remove(
            @PathVariable Long taskId,
            @PathVariable String emoji) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        // Path emoji is decoded by Spring; clients should encodeURIComponent.
        Map<String, List<PostReactionDto>> reactions = service.remove(taskId, actor, emoji);
        return ResponseEntity.ok(reactions);
    }

    @GetMapping
    public ResponseEntity<Map<String, List<PostReactionDto>>> list(@PathVariable Long taskId) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.loadByTaskId(taskId));
    }

    public record AddReactionRequest(String emoji) {}
}
