package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.PostReactionDto;
import io.sitprep.sitprepapi.service.PostReactionService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Emoji reactions on posts. The viewer is always the verified Firebase token
 * email — clients can't react on someone else's behalf.
 *
 *   POST   /api/posts/{id}/reactions { emoji }   -> add (idempotent)
 *   DELETE /api/posts/{id}/reactions/{emoji}     -> remove
 *   GET    /api/posts/{id}/reactions             -> roster lookup
 */
@RestController
@RequestMapping("/api/posts/{postId}/reactions")
public class PostReactionResource {

    private final PostReactionService service;

    public PostReactionResource(PostReactionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, List<PostReactionDto>>> add(
            @PathVariable Long postId,
            @RequestBody AddReactionRequest body) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        Map<String, List<PostReactionDto>> reactions =
                service.add(postId, actor, body == null ? null : body.emoji());
        return ResponseEntity.ok(reactions);
    }

    @DeleteMapping("/{emoji}")
    public ResponseEntity<Map<String, List<PostReactionDto>>> remove(
            @PathVariable Long postId,
            @PathVariable String emoji) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        // Path emoji is decoded by Spring; clients should encodeURIComponent.
        Map<String, List<PostReactionDto>> reactions = service.remove(postId, actor, emoji);
        return ResponseEntity.ok(reactions);
    }

    @GetMapping
    public ResponseEntity<Map<String, List<PostReactionDto>>> list(@PathVariable Long postId) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.loadByPostId(postId));
    }

    public record AddReactionRequest(String emoji) {}
}
