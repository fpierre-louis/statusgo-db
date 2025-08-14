package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Comment;
import io.sitprep.sitprepapi.dto.CommentDto;
import io.sitprep.sitprepapi.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    public ResponseEntity<Comment> createComment(@RequestBody Comment comment) {
        Comment newComment = commentService.createComment(comment);
        return ResponseEntity.ok(newComment);
    }

    // 🟢 CORRECTED: Accept List<String> to handle temporary IDs gracefully
    @GetMapping
    public Map<Long, List<CommentDto>> getCommentsBatch(
            @RequestParam List<String> postIds,
            @RequestParam(required = false) Integer limitPerPost) {

        // Filter for valid numeric IDs
        List<Long> validPostIds = postIds.stream()
                .map(id -> {
                    try {
                        return Long.parseLong(id);
                    } catch (NumberFormatException e) {
                        return null; // Ignore non-numeric IDs
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        if (validPostIds.isEmpty()) {
            return Collections.emptyMap();
        }

        return commentService.getCommentsForPosts(validPostIds, limitPerPost);
    }

    @GetMapping("/{postId}")
    public ResponseEntity<List<Comment>> getCommentsByPostId(@PathVariable Long postId) {
        List<Comment> comments = commentService.getCommentsByPostId(postId);
        return ResponseEntity.ok(comments);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Comment> updateComment(@PathVariable Long id, @RequestBody Comment commentDetails) {
        Optional<Comment> optionalComment = commentService.getCommentById(id);
        if (optionalComment.isPresent()) {
            Comment comment = optionalComment.get();
            comment.setContent(commentDetails.getContent());
            comment.setTimestamp(commentDetails.getTimestamp());
            Comment updatedComment = commentService.updateComment(comment);
            return ResponseEntity.ok(updatedComment);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteComment(@PathVariable Long id) {
        commentService.deleteComment(id);
        return ResponseEntity.noContent().build();
    }
}