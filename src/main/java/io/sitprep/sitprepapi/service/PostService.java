package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.GroupUrlUtil;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;

import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Base64;

@Service
public class PostService {

    private static final Logger logger = LoggerFactory.getLogger(PostService.class);

    private final PostRepo postRepo;
    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;
    private final NotificationService notificationService;
    private final GroupService groupService;
    private final WebSocketMessageSender webSocketMessageSender;

    @Autowired
    public PostService(PostRepo postRepo, UserInfoRepo userInfoRepo, GroupRepo groupRepo,
                       NotificationService notificationService, GroupService groupService,
                       WebSocketMessageSender webSocketMessageSender) {
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
        this.notificationService = notificationService;
        this.groupService = groupService;
        this.webSocketMessageSender = webSocketMessageSender;
    }

    // ---------- READ paths that return DTOs (fast + safe to serialize) ----------

    @Transactional
    public Post createPostFromDto(PostDto postDto, String authenticatedUserEmail) throws IOException {
        if (!postDto.getAuthor().equals(authenticatedUserEmail)) {
            logger.warn("⚠️ Unauthorized CREATE POST attempt. DTO author: {} != auth user {}", postDto.getAuthor(), authenticatedUserEmail);
            throw new SecurityException("User not authorized to create a post for another user.");
        }

        Post post = new Post();
        post.setAuthor(postDto.getAuthor());
        post.setContent(postDto.getContent());
        post.setGroupId(postDto.getGroupId());
        post.setGroupName(postDto.getGroupName());
        post.setTimestamp(Instant.now());
        post.setTags(postDto.getTags());
        post.setMentions(postDto.getMentions());

        if (postDto.getBase64Image() != null && !postDto.getBase64Image().isEmpty()) {
            String encodedImage = postDto.getBase64Image().split(",")[1];
            post.setImage(Base64.getDecoder().decode(encodedImage));
        }

        Post savedPost = postRepo.save(post);
        PostDto savedPostDto = convertToPostDto(savedPost);
        savedPostDto.setTempId(postDto.getTempId());

        notifyGroupMembersOfNewPost(savedPost);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                webSocketMessageSender.sendNewPost(savedPost.getGroupId(), savedPostDto);
            }
        });

        logger.info("🚀 Successfully processed and scheduled broadcast for post ID: {}", savedPost.getId());
        return savedPost;
    }

    @Transactional
    public Post updatePost(Post post, MultipartFile imageFile) throws IOException {
        String authenticatedUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        if (!post.getAuthor().equals(authenticatedUserEmail)) {
            logger.warn("⚠️ Unauthorized UPDATE on post {} by {}", post.getId(), authenticatedUserEmail);
            throw new SecurityException("User not authorized to update this post.");
        }

        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes());
        }

        Post updatedPost = postRepo.save(post);
        PostDto updatedPostDto = convertToPostDto(updatedPost);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                webSocketMessageSender.sendNewPost(updatedPost.getGroupId(), updatedPostDto);
            }
        });

        logger.info("🚀 Scheduled broadcast for updated post ID: {}", updatedPost.getId());
        return updatedPost;
    }

    @Transactional
    public void deletePostAndBroadcast(Long postId, String requestingUserEmail) {
        Optional<Post> postOpt = postRepo.findById(postId);
        if (postOpt.isEmpty()) throw new RuntimeException("Post not found");

        Post post = postOpt.get();
        if (!post.getAuthor().equalsIgnoreCase(requestingUserEmail)) {
            throw new SecurityException("User not authorized to delete this post.");
        }

        postRepo.delete(post);
        webSocketMessageSender.sendPostDeletion(post.getGroupId(), post.getId());
        logger.info("🗑️ Post ID {} deleted by {}", postId, requestingUserEmail);
    }

    @Transactional
    public void updatePostFromDto(PostDto dto) {
        String authenticatedUserEmail = dto.getAuthor();

        Post post = postRepo.findById(dto.getId())
                .orElseThrow(() -> new IllegalArgumentException("Post not found for update: " + dto.getId()));

        if (!post.getAuthor().equals(authenticatedUserEmail)) {
            logger.warn("⚠️ Unauthorized WebSocket EDIT on post {} by {}", dto.getId(), authenticatedUserEmail);
            throw new SecurityException("Not allowed to edit this post.");
        }

        post.setContent(dto.getContent());
        post.setEditedAt(Instant.now());
        post.setTags(dto.getTags());
        post.setMentions(dto.getMentions());

        if (dto.getBase64Image() != null && !dto.getBase64Image().isEmpty()) {
            String encodedImage = dto.getBase64Image().split(",")[1];
            post.setImage(Base64.getDecoder().decode(encodedImage));
        }

        Post updated = postRepo.save(post);
        PostDto updatedDto = convertToPostDto(updated);
        updatedDto.setTempId(dto.getTempId());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                webSocketMessageSender.sendNewPost(updated.getGroupId(), updatedDto);
            }
        });

        logger.info("✏️ Edited post via WebSocket. ID: {}", updated.getId());
    }

    // ---------- Read helpers that map to DTOs (for controllers) ----------

    @Transactional
    public void addReaction(Post post, String reaction) {
        post.getReactions().put(reaction, post.getReactions().getOrDefault(reaction, 0) + 1);
        Post updatedPost = postRepo.save(post);

        webSocketMessageSender.sendGenericUpdate(
                "/topic/posts/" + updatedPost.getGroupId() + "/reactions/" + updatedPost.getId(),
                updatedPost.getReactions()
        );
    }

    public List<Post> getPostsByGroupId(String groupId) {
        return postRepo.findPostsByGroupId(groupId);
    }

    public Optional<Post> getPostById(Long id) {
        return postRepo.findById(id);
    }

    // --- DTO-facing methods used by the resource layer ---

    @Transactional
    public List<PostDto> getPostDtosByGroupId(String groupId) {
        List<Post> posts = postRepo.findPostsByGroupId(groupId);
        return posts.stream().map(this::convertToPostDto).toList();
    }

    @Transactional
    public Optional<PostDto> getPostDtoById(Long id) {
        return postRepo.findById(id).map(this::convertToPostDto);
    }

    public PostDto toDto(Post post) {
        return convertToPostDto(post);
    }

    // ---------- Private mapping & notifications ----------

    private void notifyGroupMembersOfNewPost(Post post) {
        Optional<Group> groupOpt = groupRepo.findByGroupId(post.getGroupId());
        if (groupOpt.isEmpty()) {
            logger.warn("⚠️ Group with ID {} not found for FCM notification.", post.getGroupId());
            return;
        }

        Group group = groupOpt.get();
        List<String> recipients = group.getMemberEmails().stream()
                .filter(email -> !email.equalsIgnoreCase(post.getAuthor()))
                .collect(Collectors.toList());

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(recipients);
        Set<String> tokens = users.stream()
                .map(UserInfo::getFcmtoken)
                .filter(token -> token != null && !token.isEmpty())
                .collect(Collectors.toSet());

        if (tokens.isEmpty()) {
            logger.warn("No FCM tokens found for group members in group: {}", group.getGroupName());
            return;
        }

        Optional<UserInfo> authorOpt = userInfoRepo.findByUserEmail(post.getAuthor());
        String authorFirstName = authorOpt.map(UserInfo::getUserFirstName).orElse("Someone");
        String authorProfileImageUrl = authorOpt.map(UserInfo::getProfileImageURL).orElse("/images/default-user-icon.png");

        String notificationTitle = post.getGroupName();
        String notificationBody = String.format("%s posted in %s: '%s'",
                authorFirstName,
                post.getGroupName(),
                post.getContent().length() > 50 ? post.getContent().substring(0, 50) + "..." : post.getContent());

        String baseTargetUrl = GroupUrlUtil.getGroupTargetUrl(group);

        notificationService.sendNotification(
                notificationTitle,
                notificationBody,
                authorFirstName,
                authorProfileImageUrl,
                tokens,
                "post_notification",
                post.getGroupId(),
                baseTargetUrl + "?postId=" + post.getId(),
                String.valueOf(post.getId())
        );

        logger.info("📣 Sent FCM notification for group '{}' to {} members.", group.getGroupName(), tokens.size());
    }

    private PostDto convertToPostDto(Post post) {
        PostDto dto = new PostDto();
        dto.setId(post.getId());
        dto.setAuthor(post.getAuthor());
        dto.setContent(post.getContent());
        dto.setGroupId(String.valueOf(post.getGroupId()));
        dto.setGroupName(post.getGroupName());
        dto.setTimestamp(post.getTimestamp());

        if (post.getImage() != null) {
            String base64Image = Base64.getEncoder().encodeToString(post.getImage());
            dto.setBase64Image("data:image/jpeg;base64," + base64Image);
        }

        dto.setEditedAt(post.getEditedAt());
        dto.setReactions(post.getReactions() != null ? post.getReactions() : new HashMap<>());
        dto.setTags(post.getTags() != null ? post.getTags() : new ArrayList<>());
        dto.setMentions(post.getMentions() != null ? post.getMentions() : new ArrayList<>());
        dto.setCommentsCount(post.getCommentsCount());

        userInfoRepo.findByUserEmail(post.getAuthor()).ifPresent(authorInfo -> {
            dto.setAuthorFirstName(authorInfo.getUserFirstName());
            dto.setAuthorLastName(authorInfo.getUserLastName());
            dto.setAuthorProfileImageURL(authorInfo.getProfileImageURL());
        });

        return dto;
    }
}
