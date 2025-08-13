package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.service.PostService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/posts")
public class PostResource {

    @Autowired
    private PostService postService;

    // Return DTOs (lighter payloads; base64 image handled in mapping)
    @GetMapping("/group/{groupId}")
    public List<PostDto> getPostsByGroupId(@PathVariable String groupId) {
        return postService.getPostDtosByGroupId(groupId);
    }

    @GetMapping("/{postId}")
    public ResponseEntity<PostDto> getPostById(@PathVariable Long postId) {
        Optional<PostDto> postOpt = postService.getPostDtoById(postId);
        return postOpt.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/{postId}", consumes = {"multipart/form-data"})
    public ResponseEntity<PostDto> updatePost(
            @PathVariable Long postId,
            @RequestParam("content") String content,
            @RequestParam("groupId") String groupId,
            @RequestParam("groupName") String groupName,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "mentions", required = false) List<String> mentions,
            @RequestParam(value = "pinned", required = false, defaultValue = "false") boolean pinned
    ) throws IOException {
        Optional<Post> postOpt = postService.getPostById(postId);
        if (postOpt.isEmpty()) return ResponseEntity.notFound().build();

        Post post = postOpt.get();
        String currentUserEmail = AuthUtils.getCurrentUserEmail();
        if (!post.getAuthor().equalsIgnoreCase(currentUserEmail)) {
            return ResponseEntity.status(403).build();
        }

        post.setContent(content);
        post.setGroupId(groupId);
        post.setGroupName(groupName);
        post.setTags(tags);
        post.setMentions(mentions);
        post.setEditedAt(Instant.now());

        Post updated = postService.updatePost(post, imageFile);
        return ResponseEntity.ok(postService.toDto(updated));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(@PathVariable Long postId) {
        String currentUserEmail = AuthUtils.getCurrentUserEmail();
        postService.deletePostAndBroadcast(postId, currentUserEmail);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{postId}/reaction")
    public ResponseEntity<PostDto> addReaction(
            @PathVariable Long postId,
            @RequestParam("reaction") String reaction
    ) {
        Optional<Post> postOpt = postService.getPostById(postId);
        if (postOpt.isEmpty()) return ResponseEntity.notFound().build();

        Post post = postOpt.get();
        postService.addReaction(post, reaction);
        return ResponseEntity.ok(postService.toDto(post));
    }
}
