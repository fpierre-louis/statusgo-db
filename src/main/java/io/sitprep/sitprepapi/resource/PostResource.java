package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.service.PostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.time.ZoneId;

@RestController
@RequestMapping("/api/posts")
public class PostResource {

    @Autowired
    private PostService postService;

    // Create post with file upload support and additional fields
    @PostMapping(consumes = { "multipart/form-data" })
    public Post createPost(
            @RequestParam("author") String author,
            @RequestParam("content") String content,
            @RequestParam("groupId") Long groupId,
            @RequestParam("groupName") String groupName,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "mentions", required = false) List<String> mentions,
            @RequestParam(value = "pinned", required = false, defaultValue = "false") boolean pinned
    ) throws IOException {
        Post post = new Post();
        post.setAuthor(author);
        post.setContent(content);
        post.setGroupId(groupId);
        post.setGroupName(groupName);
        post.setTimestamp(Instant.now());
        post.setTags(tags);
        post.setMentions(mentions);

        return postService.createPost(post, imageFile);
    }

    // Get posts by groupId with base64 encoded images
    @GetMapping("/group/{groupId}")
    public List<Post> getPostsByGroupId(@PathVariable Long groupId) {
        List<Post> posts = postService.getPostsByGroupId(groupId);
        for (Post post : posts) {
            if (post.getImage() != null) {
                String base64Image = Base64.getEncoder().encodeToString(post.getImage());
                post.setBase64Image("data:image/jpeg;base64," + base64Image);
            }
        }
        return posts;
    }

    // Update post, including support for updating image and new fields
    @PutMapping("/{postId}")
    public ResponseEntity<Post> updatePost(
            @PathVariable Long postId,
            @RequestParam("author") String author,
            @RequestParam("content") String content,
            @RequestParam("groupId") Long groupId,
            @RequestParam("groupName") String groupName,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "mentions", required = false) List<String> mentions,
            @RequestParam(value = "pinned", required = false, defaultValue = "false") boolean pinned
    ) throws IOException {
        Optional<Post> postOpt = postService.getPostById(postId);
        if (postOpt.isPresent()) {
            Post post = postOpt.get();
            post.setAuthor(author);
            post.setContent(content);
            post.setGroupId(groupId);
            post.setGroupName(groupName);
            post.setTags(tags);
            post.setMentions(mentions);

            post.setEditedAt(Instant.now());

            Post updatedPost = postService.updatePost(post, imageFile);
            return ResponseEntity.ok(updatedPost);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // Delete post by id
    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(@PathVariable Long postId) {
        postService.deletePost(postId);
        return ResponseEntity.noContent().build();
    }

    // Add or update reaction to a post
    @PostMapping("/{postId}/reaction")
    public ResponseEntity<Post> addReaction(
            @PathVariable Long postId,
            @RequestParam("reaction") String reaction
    ) {
        Optional<Post> postOpt = postService.getPostById(postId);
        if (postOpt.isPresent()) {
            Post post = postOpt.get();
            postService.addReaction(post, reaction);
            return ResponseEntity.ok(post);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
