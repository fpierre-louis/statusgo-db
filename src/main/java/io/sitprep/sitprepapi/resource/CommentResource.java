// src/main/java/io/sitprep/sitprepapi/resource/CommentResource.java
package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.CommentDto;
import io.sitprep.sitprepapi.service.CommentService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Comments on posts. Phase E enforcement on every WRITE — anonymous users
 * cannot post or edit comments. Author is the verified token email; PUT
 * and DELETE additionally verify the caller authored the target comment.
 *
 * <p>Reads stay open during the rollout — same reasoning as {@code Post}.
 * Public-group comment threads are visible without auth, and share-link
 * OG generation may hit them anonymously.</p>
 */
@RestController
@RequestMapping("/api/comments")
public class CommentResource {
    private final CommentService commentService;

    @Autowired
    public CommentResource(CommentService commentService) {
        this.commentService = commentService;
    }

    @PostMapping
    public ResponseEntity<CommentDto> createComment(@RequestBody CommentDto dto) {
        String author = AuthUtils.requireAuthenticatedEmail();
        // Always trust the token over the body. An attacker can't post a
        // comment under another user's email even if the body says so.
        dto.setAuthor(author);
        CommentDto saved = commentService.createCommentFromDto(dto);
        return ResponseEntity.ok(saved);
    }

    // Batch: ?postIds=1&postIds=2&limitPerPost=10
    @GetMapping
    public Map<Long, List<CommentDto>> getCommentsBatch(
            @RequestParam List<String> postIds,
            @RequestParam(required = false) Integer limitPerPost) {
        var validPostIds = postIds.stream()
                .map(id -> {
                    try { return Long.parseLong(id); } catch (NumberFormatException e) { return null; }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (validPostIds.isEmpty()) return Collections.emptyMap();
        return commentService.getCommentsForPosts(validPostIds, limitPerPost);
    }

    // Backfill by updatedAt
    @GetMapping("/since")
    public List<CommentDto> getCommentsSince(
            @RequestParam Long postId,
            @RequestParam String sinceIso) {
        return commentService.getCommentsSince(postId, Instant.parse(sinceIso));
    }

    // All for a post (oldest -> newest)
    @GetMapping("/{postId}")
    public ResponseEntity<List<CommentDto>> getCommentsByPostId(@PathVariable Long postId) {
        return ResponseEntity.ok(commentService.getCommentsByPostId(postId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CommentDto> updateComment(@PathVariable Long id, @RequestBody CommentDto dto) {
        ensureAuthors(id);
        dto.setId(id);
        // Author can't be changed on edit — preserve the original.
        CommentDto updated = commentService.updateCommentFromDto(dto);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteComment(@PathVariable Long id) {
        ensureAuthors(id);
        commentService.deleteCommentByIdAndBroadcast(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 404 if the comment doesn't exist (don't leak ids), 403 if the caller
     * isn't its author. Returns the comment for downstream use if needed.
     */
    private void ensureAuthors(Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<CommentDto> existing = commentService.getCommentById(id);
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
