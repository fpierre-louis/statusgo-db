package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.dto.PostSummaryDto;
import io.sitprep.sitprepapi.service.PostService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Base64;

@RestController
@RequestMapping("/api/posts")
public class PostResource {

    @Autowired
    private PostService postService;

    @PostMapping(value = "", consumes = { "multipart/form-data" })
    public ResponseEntity<PostDto> createPost(
            @RequestParam("content") String content,
            @RequestParam("groupId") String groupId,
            @RequestParam("groupName") String groupName,
            @RequestParam("authorEmail") String authorEmail,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile
    ) {
        try {
            final String postAuthor = authorEmail;
            if (postAuthor == null || postAuthor.isBlank()) {
                return ResponseEntity.status(400).body(null);
            }

            // 1. Build DTO: Only set author and text content.
            PostDto postDto = new PostDto();
            postDto.setAuthor(postAuthor);
            postDto.setContent(content);
            postDto.setGroupId(groupId);
            postDto.setGroupName(groupName);

            // 2. Create post: Pass DTO and raw MultipartFile.
            PostDto savedPost = postService.createPostWithFile(postDto, imageFile, postAuthor);

            return ResponseEntity.status(201).body(savedPost);

        } catch (Exception e) {
            // *** CRITICAL DIAGNOSTIC STEP ***
            // Log the exception clearly and rethrow as a RuntimeException to ensure
            // the full stack trace is printed to the console for diagnosis.
            System.err.println("❌ CRITICAL EXCEPTION: REST createPost failed!");
            e.printStackTrace();

            // Re-throw the exception. This maintains the 500 response status.
            throw new RuntimeException("Post upload failed: " + e.getMessage(), e);

            // If you absolutely must return a ResponseEntity:
            // return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/group/{groupId}")
    public List<PostDto> getPostsByGroupId(@PathVariable String groupId) {
        return postService.getPostsByGroupIdDto(groupId);
    }

    // Backfill: posts since timestamp for a group
    @GetMapping("/since")
    public List<PostDto> getPostsSince(
            @RequestParam String groupId,
            @RequestParam String sinceIso
    ) {
        return postService.getPostsByGroupSince(groupId, Instant.parse(sinceIso));
    }

    @GetMapping("/groups/latest")
    public Map<String, PostSummaryDto> getLatestPostsForGroups(
            @RequestParam("groupIds") List<String> groupIds) {
        return postService.getLatestPostsForGroups(groupIds);
    }

    @GetMapping("/{postId}")
    public ResponseEntity<PostDto> getPostById(@PathVariable Long postId) {
        Optional<PostDto> postOpt = postService.getPostDtoById(postId);
        return postOpt.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/{postId}", consumes = { "multipart/form-data" })
    public ResponseEntity<Post> updatePost(
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
        // Since JWT is off, we still attempt to verify against the current context user.
        String currentUserEmail = AuthUtils.getCurrentUserEmail();
        if (!post.getAuthor().equalsIgnoreCase(currentUserEmail) && !currentUserEmail.equalsIgnoreCase("anonymous")) {
            return ResponseEntity.status(403).build();
        }

        post.setContent(content);
        post.setGroupId(groupId);
        post.setGroupName(groupName);
        post.setTags(tags);
        post.setMentions(mentions);
        post.setEditedAt(Instant.now());

        Post updatedPost = postService.updatePost(post, imageFile);
        return ResponseEntity.ok(updatedPost);
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(@PathVariable Long postId) {
        String currentUserEmail = AuthUtils.getCurrentUserEmail();
        postService.deletePostAndBroadcast(postId, currentUserEmail);
        return ResponseEntity.noContent().build();
    }
}