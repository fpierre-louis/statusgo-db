package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.dto.GroupPostDto;
import io.sitprep.sitprepapi.dto.GroupPostPageDto;
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

    /**
     * Cursor-paginated chat-feed listing. Canonical surface for the
     * chat-style feed scroll on OrgGroupPage / HouseholdFamily chat.
     * Replaces the un-paginated {@link #getPostsByGroupId} when groups
     * accumulate enough posts that loading the whole history at once
     * becomes a phone-memory / network drag.
     *
     * <p>Query params:</p>
     * <ul>
     *   <li>{@code before} (optional Long) — cursor; returns posts older
     *       than this id. Omit for the first / latest page.</li>
     *   <li>{@code limit} (optional Integer) — page size, clamped to
     *       [1, 200]. Defaults to 50 (mirrors PostCommentService).</li>
     * </ul>
     *
     * <p>Response shape (see {@link GroupPostPageDto}):</p>
     * <pre>
     * {
     *   "pinned":     [GroupPostDto],   // all pinned posts, always
     *   "items":      [GroupPostDto],   // page of unpinned posts
     *   "nextBefore": 1234,             // cursor for next page (null if exhausted)
     *   "hasMore":    true              // "Load earlier" affordance flag
     * }
     * </pre>
     */
    @GetMapping("/group/{groupId}/page")
    public GroupPostPageDto getPostsByGroupIdPage(
            @PathVariable String groupId,
            @RequestParam(value = "before", required = false) Long before,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        AuthUtils.requireAuthenticatedEmail();
        return postService.getPostsByGroupIdPage(groupId, before, limit);
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

    /**
     * Pin a group post to the top of its group's feed. Admin/owner-only —
     * the service layer rejects non-admin callers with 403. Returns the
     * updated DTO (with {@code pinnedAt} + {@code pinnedBy} populated)
     * so the FE can patch its local cache without re-fetching the feed.
     * Broadcasts via the same {@code /topic/group-posts/{groupId}} WS
     * channel as a fresh post so every member's open feed shows the
     * pin instantly.
     */
    @PostMapping("/{postId}/pin")
    public ResponseEntity<GroupPostDto> pinPost(@PathVariable Long postId) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        try {
            return ResponseEntity.ok(postService.pinPost(postId, actor));
        } catch (SecurityException se) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, se.getMessage());
        }
    }

    /**
     * Unpin a previously-pinned post. Same admin gate as
     * {@link #pinPost(Long)}. Idempotent — unpinning a not-pinned post
     * is a no-op (no error). Returns the updated DTO so the FE drops
     * the pin chip on the next render.
     */
    @DeleteMapping("/{postId}/pin")
    public ResponseEntity<GroupPostDto> unpinPost(@PathVariable Long postId) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        try {
            return ResponseEntity.ok(postService.unpinPost(postId, actor));
        } catch (SecurityException se) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, se.getMessage());
        }
    }
}
