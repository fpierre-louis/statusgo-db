// src/main/java/io/sitprep/sitprepapi/resource/GroupPostCommentResource.java
package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.GroupPostCommentDto;
import io.sitprep.sitprepapi.service.GroupPostCommentService;
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
 * <p>Reads require a verified token. GroupPostComment threads belong to groups
 * (private to members) so anonymous reads aren't a real use case in this
 * app — if a future flow needs them (e.g. public-link OG generation),
 * carve a dedicated endpoint instead of opening this one back up.</p>
 */
@RestController
@RequestMapping("/api/group-post-comments")
public class GroupPostCommentResource {
    private final GroupPostCommentService commentService;

    @Autowired
    public GroupPostCommentResource(GroupPostCommentService commentService) {
        this.commentService = commentService;
    }

    @PostMapping
    public ResponseEntity<GroupPostCommentDto> createComment(@RequestBody GroupPostCommentDto dto) {
        String author = AuthUtils.requireAuthenticatedEmail();
        // Always trust the token over the body. An attacker can't post a
        // comment under another user's email even if the body says so.
        dto.setAuthor(author);
        GroupPostCommentDto saved = commentService.createCommentFromDto(dto);
        return ResponseEntity.ok(saved);
    }

    // Batch: ?postIds=1&postIds=2&limitPerPost=10
    @GetMapping
    public Map<Long, List<GroupPostCommentDto>> getCommentsBatch(
            @RequestParam List<String> postIds,
            @RequestParam(required = false) Integer limitPerPost) {
        // Pass viewer through so each comment in the response carries
        // viewerThanked — the FE renders the heart filled when the
        // viewer has already reacted on a given comment.
        String viewer = AuthUtils.requireAuthenticatedEmail();
        var validPostIds = postIds.stream()
                .map(id -> {
                    try { return Long.parseLong(id); } catch (NumberFormatException e) { return null; }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (validPostIds.isEmpty()) return Collections.emptyMap();
        return commentService.getCommentsForPosts(validPostIds, limitPerPost, viewer);
    }

    // Backfill by updatedAt
    @GetMapping("/since")
    public List<GroupPostCommentDto> getCommentsSince(
            @RequestParam Long postId,
            @RequestParam String sinceIso) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        return commentService.getCommentsSince(postId, Instant.parse(sinceIso), viewer);
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
     */
    @GetMapping("/{postId}")
    public ResponseEntity<List<GroupPostCommentDto>> getCommentsByPostId(
            @PathVariable Long postId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "beforeId", required = false) Long beforeId) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        if (limit != null || beforeId != null) {
            int safeLimit = (limit == null) ? 30 : limit;
            return ResponseEntity.ok(commentService.getCommentsPage(postId, viewer, beforeId, safeLimit));
        }
        return ResponseEntity.ok(commentService.getCommentsByPostId(postId, viewer));
    }

    @PutMapping("/{id}")
    public ResponseEntity<GroupPostCommentDto> updateComment(@PathVariable Long id, @RequestBody GroupPostCommentDto dto) {
        ensureAuthors(id);
        dto.setId(id);
        // Author can't be changed on edit — preserve the original.
        GroupPostCommentDto updated = commentService.updateCommentFromDto(dto);
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
        Optional<GroupPostCommentDto> existing = commentService.getCommentById(id);
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String author = existing.get().getAuthor();
        if (author == null || !author.equalsIgnoreCase(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "GroupPostComment belongs to a different user");
        }
    }
}
