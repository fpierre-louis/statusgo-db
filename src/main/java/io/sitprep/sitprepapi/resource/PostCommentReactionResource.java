package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.EmojiReactionDto;
import io.sitprep.sitprepapi.service.PostCommentReactionService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Emoji reactions on community feed comments. Mirrors
 * {@code PostReactionResource} (post-level reactions) modulo the path
 * prefix:
 *
 * <pre>
 *   POST   /api/post-comments/{id}/reactions { emoji }   -> add (idempotent)
 *   DELETE /api/post-comments/{id}/reactions/{emoji}     -> remove
 *   GET    /api/post-comments/{id}/reactions             -> roster lookup
 * </pre>
 *
 * <p>The roster shape ({@code Map<String, List<EmojiReactionDto>>})
 * is reused from the post-level reactions so the FE can share the same
 * render code regardless of source surface.</p>
 *
 * <p>The viewer is always the verified Firebase token email — clients
 * can't react on someone else's behalf.</p>
 */
@RestController
@RequestMapping("/api/post-comments/{postCommentId}/reactions")
public class PostCommentReactionResource {

    private final PostCommentReactionService service;

    public PostCommentReactionResource(PostCommentReactionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, List<EmojiReactionDto>>> add(
            @PathVariable Long postCommentId,
            @RequestBody AddReactionRequest body) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        Map<String, List<EmojiReactionDto>> reactions =
                service.add(postCommentId, actor, body == null ? null : body.emoji());
        return ResponseEntity.ok(reactions);
    }

    @DeleteMapping("/{emoji}")
    public ResponseEntity<Map<String, List<EmojiReactionDto>>> remove(
            @PathVariable Long postCommentId,
            @PathVariable String emoji) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        // Path emoji is decoded by Spring; clients should encodeURIComponent.
        Map<String, List<EmojiReactionDto>> reactions = service.remove(postCommentId, actor, emoji);
        return ResponseEntity.ok(reactions);
    }

    @GetMapping
    public ResponseEntity<Map<String, List<EmojiReactionDto>>> list(@PathVariable Long postCommentId) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.loadByPostCommentId(postCommentId));
    }

    public record AddReactionRequest(String emoji) {}
}
