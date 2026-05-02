package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.dto.GroupPostDto;
import io.sitprep.sitprepapi.dto.GroupPostSummaryDto;
import io.sitprep.sitprepapi.service.GroupPostService;
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
@RequestMapping("/api/group-posts")
public class GroupPostResource {

    @Autowired
    private GroupPostService postService;

    @PostMapping(value = "", consumes = { "multipart/form-data" })
    public ResponseEntity<GroupPostDto> createPost(
            @RequestParam("content") String content,
            @RequestParam("groupId") String groupId,
            @RequestParam("groupName") String groupName,
            @RequestParam(value = "imageKey", required = false) String imageKey,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "mentions", required = false) List<String> mentions
    ) {
        String author = AuthUtils.requireAuthenticatedEmail();

        GroupPostDto postDto = new GroupPostDto();
        postDto.setAuthor(author);
        postDto.setContent(content);
        postDto.setGroupId(groupId);
        postDto.setGroupName(groupName);
        postDto.setImageKey(imageKey);
        postDto.setTags(tags);
        postDto.setMentions(mentions);

        GroupPostDto saved = postService.createPost(postDto, author);
        return ResponseEntity.status(201).body(saved);
    }

    @GetMapping("/group/{groupId}")
    public List<GroupPostDto> getPostsByGroupId(@PathVariable String groupId) {
        AuthUtils.requireAuthenticatedEmail();
        return postService.getPostsByGroupIdDto(groupId);
    }

    @GetMapping("/since")
    public List<GroupPostDto> getPostsSince(
            @RequestParam String groupId,
            @RequestParam String sinceIso
    ) {
        AuthUtils.requireAuthenticatedEmail();
        return postService.getPostsByGroupSince(groupId, Instant.parse(sinceIso));
    }

    @GetMapping("/groups/latest")
    public Map<String, GroupPostSummaryDto> getLatestPostsForGroups(
            @RequestParam("groupIds") List<String> groupIds) {
        AuthUtils.requireAuthenticatedEmail();
        return postService.getLatestPostsForGroups(groupIds);
    }

    @GetMapping("/{postId}")
    public ResponseEntity<GroupPostDto> getPostById(@PathVariable Long postId) {
        AuthUtils.requireAuthenticatedEmail();
        Optional<GroupPostDto> postOpt = postService.getPostDtoById(postId);
        return postOpt.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/{postId}", consumes = { "multipart/form-data" })
    public ResponseEntity<GroupPost> updatePost(
            @PathVariable Long postId,
            @RequestParam("content") String content,
            @RequestParam("groupId") String groupId,
            @RequestParam("groupName") String groupName,
            @RequestParam(value = "imageKey", required = false) String imageKey,
            @RequestParam(value = "removeImage", required = false) Boolean removeImage,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "mentions", required = false) List<String> mentions
    ) {
        String actor = AuthUtils.requireAuthenticatedEmail();

        Optional<GroupPost> postOpt = postService.getPostById(postId);
        if (postOpt.isEmpty()) return ResponseEntity.notFound().build();

        GroupPost post = postOpt.get();

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

        GroupPost updatedPost = postService.updatePost(post, actor);
        return ResponseEntity.ok(updatedPost);
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(@PathVariable Long postId) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        postService.deletePostAndBroadcast(postId, actor);
        return ResponseEntity.noContent().build();
    }
}
