package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.EmojiReactionDto;
import io.sitprep.sitprepapi.service.GroupPostCommentReactionService;
import io.sitprep.sitprepapi.service.ThreadAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Emoji reactions on group chat comments. Mirrors
 * {@code PostCommentReactionResource} (community-feed comment
 * reactions) modulo the path prefix:
 *
 * <pre>
 *   POST   /api/group-post-comments/{id}/reactions { emoji }   -> add (idempotent)
 *   DELETE /api/group-post-comments/{id}/reactions/{emoji}     -> remove
 *   GET    /api/group-post-comments/{id}/reactions             -> roster lookup
 * </pre>
 *
 * <p>The viewer is always the verified Firebase token email — clients
 * can't react on someone else's behalf.</p>
 */
@RestController
@RequestMapping("/api/group-post-comments/{groupPostCommentId}/reactions")
public class GroupPostCommentReactionResource {

    private final GroupPostCommentReactionService service;
    private final ThreadAccessService threadAccess;

    public GroupPostCommentReactionResource(GroupPostCommentReactionService service,
                                            ThreadAccessService threadAccess) {
        this.service = service;
        this.threadAccess = threadAccess;
    }

    @PostMapping
    public ResponseEntity<Map<String, List<EmojiReactionDto>>> add(
            @PathVariable Long groupPostCommentId,
            @RequestBody AddReactionRequest body) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        threadAccess.requireCanAccessGroupPostComment(groupPostCommentId, actor);
        Map<String, List<EmojiReactionDto>> reactions =
                service.add(groupPostCommentId, actor, body == null ? null : body.emoji());
        return ResponseEntity.ok(reactions);
    }

    @DeleteMapping("/{emoji}")
    public ResponseEntity<Map<String, List<EmojiReactionDto>>> remove(
            @PathVariable Long groupPostCommentId,
            @PathVariable String emoji) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        threadAccess.requireCanAccessGroupPostComment(groupPostCommentId, actor);
        // Path emoji is decoded by Spring; clients should encodeURIComponent.
        Map<String, List<EmojiReactionDto>> reactions =
                service.remove(groupPostCommentId, actor, emoji);
        return ResponseEntity.ok(reactions);
    }

    @GetMapping
    public ResponseEntity<Map<String, List<EmojiReactionDto>>> list(
            @PathVariable Long groupPostCommentId) {
        threadAccess.requireCanAccessGroupPostComment(
                groupPostCommentId, AuthUtils.requireAuthenticatedEmail());
        return ResponseEntity.ok(service.loadByGroupPostCommentId(groupPostCommentId));
    }

    public record AddReactionRequest(String emoji) {}
}
