package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.service.PostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/posts")
public class PostResource {

    @Autowired
    private PostService postService;

    @PostMapping
    public Post createPost(@RequestBody Post post) {
        return postService.createPost(post);
    }

    @GetMapping("/group/{groupId}")
    public List<Post> getPostsByGroupId(@PathVariable Long groupId) {
        return postService.getPostsByGroupId(groupId);
    }

    @GetMapping("/{postId}")
    public ResponseEntity<Post> getPostById(@PathVariable Long postId) {
        Optional<Post> postOpt = postService.getPostById(postId);
        return postOpt.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{postId}")
    public ResponseEntity<Post> updatePost(@PathVariable Long postId, @RequestBody Post postDetails) {
        Optional<Post> postOpt = postService.getPostById(postId);
        if (postOpt.isPresent()) {
            Post post = postOpt.get();
            post.setAuthor(postDetails.getAuthor());
            post.setContent(postDetails.getContent());
            post.setGroupId(postDetails.getGroupId());
            post.setGroupName(postDetails.getGroupName());
            post.setTimestamp(postDetails.getTimestamp());

            Post updatedPost = postService.updatePost(post);
            return ResponseEntity.ok(updatedPost);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(@PathVariable Long postId) {
        postService.deletePost(postId);
        return ResponseEntity.noContent().build();
    }
}
