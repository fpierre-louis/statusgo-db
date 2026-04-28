package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.dto.PostSummaryDto;
import io.sitprep.sitprepapi.service.PostService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Posts API. Image bytes go through {@code POST /api/images} (R2);
 * this endpoint accepts the resulting {@code imageKey} on the body.
 * The old multipart {@code imageFile} parameter is gone.
 */
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
            @RequestParam(value = "authorEmail", required = false) String authorEmail,
            @RequestParam(value = "imageKey", required = false) String imageKey,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "mentions", required = false) List<String> mentions
    ) {
        // Phase E: author is the verified token email. authorEmail param is
        // ignored (kept on the signature for back-compat with old clients
        // until the next mobile build forces a refresh).
        String author = AuthUtils.requireAuthenticatedEmail();

        PostDto postDto = new PostDto();
        postDto.setAuthor(author);
        postDto.setContent(content);
        postDto.setGroupId(groupId);
        postDto.setGroupName(groupName);
        postDto.setImageKey(imageKey);
        postDto.setTags(tags);
        postDto.setMentions(mentions);

        PostDto saved = postService.createPost(postDto, author);
        return ResponseEntity.status(201).body(saved);
    }

    @GetMapping("/group/{groupId}")
    public List<PostDto> getPostsByGroupId(@PathVariable String groupId) {
        return postService.getPostsByGroupIdDto(groupId);
    }

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
            @RequestParam(value = "imageKey", required = false) String imageKey,
            @RequestParam(value = "removeImage", required = false) Boolean removeImage,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "mentions", required = false) List<String> mentions,
            @RequestParam(value = "authorEmail", required = false) String authorEmail
    ) {
        // Phase E: must be signed in AND own the post.
        String actor = AuthUtils.requireAuthenticatedEmail();

        Optional<Post> postOpt = postService.getPostById(postId);
        if (postOpt.isEmpty()) return ResponseEntity.notFound().build();

        Post post = postOpt.get();

        if (post.getAuthor() == null || !post.getAuthor().equalsIgnoreCase(actor)) {
            return ResponseEntity.status(403).build();
        }

        post.setContent(content);
        post.setGroupId(groupId);
        post.setGroupName(groupName);
        post.setTags(tags);
        post.setMentions(mentions);
        post.setEditedAt(Instant.now());

        // Image lifecycle on edit:
        //   - imageKey present  → replace
        //   - removeImage=true  → clear
        //   - both absent       → leave unchanged
        if (imageKey != null && !imageKey.isBlank()) {
            post.setImageKey(imageKey.trim());
        } else if (Boolean.TRUE.equals(removeImage)) {
            post.setImageKey(null);
        }

        Post updatedPost = postService.updatePost(post, actor);
        return ResponseEntity.ok(updatedPost);
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(
            @PathVariable Long postId,
            @RequestParam(value = "actor", required = false) String actorParam
    ) {
        // Phase E: actor is the verified email. PostService still throws
        // SecurityException if the post belongs to someone else, so the
        // ownership check is enforced at the service layer.
        String actor = AuthUtils.requireAuthenticatedEmail();
        postService.deletePostAndBroadcast(postId, actor);
        return ResponseEntity.noContent().build();
    }
}
