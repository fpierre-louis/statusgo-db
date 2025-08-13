// CommentResource.java
package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Comment;
import io.sitprep.sitprepapi.dto.CommentDto;   // <-- add this
import io.sitprep.sitprepapi.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

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

    // GET /api/comments?postIds=1&postIds=2&limitPerPost=3
    @GetMapping
    public java.util.Map<Long, java.util.List<CommentDto>> getCommentsBatch(
            @RequestParam java.util.List<Long> postIds,
            @RequestParam(required = false) Integer limitPerPost) {
        return commentService.getCommentsForPosts(postIds, limitPerPost);
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
