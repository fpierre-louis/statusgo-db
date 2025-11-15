// src/main/java/io/sitprep/sitprepapi/resource/CommentResource.java
package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.CommentDto;
import io.sitprep.sitprepapi.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        dto.setId(id);
        CommentDto updated = commentService.updateCommentFromDto(dto);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteComment(@PathVariable Long id) {
        commentService.deleteCommentByIdAndBroadcast(id);
        return ResponseEntity.noContent().build();
    }
}