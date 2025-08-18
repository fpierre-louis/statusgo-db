package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.GroupUrlUtil;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.dto.PostSummaryDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;

import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Base64; // <-- needed

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

    @Transactional
    public PostDto createPostFromDto(PostDto postDto, String authenticatedUserEmail) throws IOException {
        if (!postDto.getAuthor().equals(authenticatedUserEmail)) {
            logger.warn("⚠️ Unauthorized CREATE POST attempt. DTO author: {} does not match authenticated user: {}", postDto.getAuthor(), authenticatedUserEmail);
            throw new SecurityException("User not authorized to create a post for another user.");
        }

        Post post = new Post();
        post.setAuthor(postDto.getAuthor());
        post.setContent(postDto.getContent());
        post.setGroupId(postDto.getGroupId());
        post.setGroupName(postDto.getGroupName());
        // createdAt is handled by auditing, but keeping this is fine if you want immediate value
        post.setTimestamp(Instant.now());
        post.setTags(postDto.getTags());
        post.setMentions(postDto.getMentions());

        if (postDto.getBase64Image() != null && !postDto.getBase64Image().isEmpty()) {
            String encodedImage = postDto.getBase64Image().split(",")[1];
            byte[] imageBytes = Base64.getDecoder().decode(encodedImage);
            post.setImage(imageBytes);
        }

        Post savedPost = postRepo.save(post);
        notifyGroupMembersOfNewPost(savedPost);

        logger.info("🚀 Successfully created post ID: {}", savedPost.getId());

        return convertToPostDto(savedPost);
    }

    @Transactional
    public Post updatePost(Post post, MultipartFile imageFile) throws IOException {
        String authenticatedUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();

        if (!post.getAuthor().equals(authenticatedUserEmail)) {
            logger.warn("⚠️ Unauthorized UPDATE attempt on post ID {}. User: {}", post.getId(), authenticatedUserEmail);
            throw new SecurityException("User not authorized to update this post.");
        }

        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes());
        }

        // Do not touch createdAt (timestamp). editedAt is user-visible edit moment.
        Post updatedPost = postRepo.save(post);
        PostDto updatedPostDto = convertToPostDto(updatedPost);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                webSocketMessageSender.sendNewPost(updatedPost.getGroupId(), updatedPostDto);
            }
        });

        logger.info("🚀 Successfully scheduled broadcast for updated post ID: {}", updatedPost.getId());
        return updatedPost;
    }

    @Transactional
    public void deletePostAndBroadcast(Long postId, String requestingUserEmail) {
        Optional<Post> postOpt = postRepo.findById(postId);
        if (postOpt.isEmpty()) {
            throw new RuntimeException("Post not found");
        }

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

        Optional<Post> postOpt = postRepo.findById(dto.getId());
        if (postOpt.isEmpty()) {
            throw new IllegalArgumentException("Post not found for update: " + dto.getId());
        }

        Post post = postOpt.get();

        if (!post.getAuthor().equals(authenticatedUserEmail)) {
            logger.warn("⚠️ Unauthorized WebSocket EDIT attempt on post ID {}. User: {}", dto.getId(), authenticatedUserEmail);
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

        // Auditing will bump updatedAt automatically
        Post updated = postRepo.save(post);
        PostDto updatedDto = convertToPostDto(updated);
        updatedDto.setTempId(dto.getTempId());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                webSocketMessageSender.sendNewPost(updated.getGroupId(), updatedDto);
            }
        });

        logger.info("✏️ Edited post via WebSocket. ID: {}", updated.getId());
    }

    public List<Post> getPostsByGroupId(String groupId) {
        return postRepo.findPostsByGroupId(groupId);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<PostDto> getPostsByGroupIdDto(String groupId) {
        List<Post> posts = postRepo.findPostsByGroupId(groupId);
        if (posts.isEmpty()) return List.of();

        Set<String> emails = posts.stream()
                .map(Post::getAuthor)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        return posts.stream()
                .map(p -> convertToPostDto(p, userByEmail))
                .collect(Collectors.toList());
    }

    public Optional<Post> getPostById(Long id) {
        return postRepo.findById(id);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<PostDto> getPostDtoById(Long id) {
        return postRepo.findById(id).map(this::convertToPostDto);
    }

    @Transactional
    public void addReaction(Post post, String reaction) {
        post.getReactions().put(reaction, post.getReactions().getOrDefault(reaction, 0) + 1);
        Post updatedPost = postRepo.save(post);

        webSocketMessageSender.sendGenericUpdate(
                "/topic/posts/" + updatedPost.getGroupId() + "/reactions/" + updatedPost.getId(),
                updatedPost.getReactions()
        );
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<String, PostSummaryDto> getLatestPostsForGroups(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return Collections.emptyMap();

        List<Post> candidates = postRepo.findLatestPostsByGroupIds(groupIds);

        Map<String, Post> bestByGroup = new HashMap<>();
        for (Post p : candidates) {
            Post cur = bestByGroup.get(p.getGroupId());
            if (cur == null
                    || p.getTimestamp().isAfter(cur.getTimestamp())
                    || (p.getTimestamp().equals(cur.getTimestamp()) && p.getId() > cur.getId())) {
                bestByGroup.put(p.getGroupId(), p);
            }
        }

        Set<String> emails = bestByGroup.values().stream()
                .map(Post::getAuthor)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        Map<String, PostSummaryDto> out = new HashMap<>();
        for (var e : bestByGroup.entrySet()) {
            Post p = e.getValue();
            UserInfo u = userByEmail.get(p.getAuthor());

            PostSummaryDto dto = new PostSummaryDto();
            dto.setId(p.getId());
            dto.setGroupId(p.getGroupId());
            dto.setGroupName(p.getGroupName());
            dto.setAuthor(p.getAuthor());
            if (u != null) {
                dto.setAuthorFirstName(u.getUserFirstName());
                dto.setAuthorLastName(u.getUserLastName());
                dto.setAuthorProfileImageURL(u.getProfileImageURL());
            }
            dto.setContent(p.getContent());
            dto.setTimestamp(p.getTimestamp());

            out.put(e.getKey(), dto);
        }

        return out;
    }

    /** 🔹 Delta/backfill for a group: everything changed after `since` (ordered ascending) */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<PostDto> getPostsSinceDto(String groupId, Instant since) {
        List<Post> posts = postRepo.findByGroupIdAndUpdatedAtAfterOrderByUpdatedAtAsc(groupId, since);
        if (posts.isEmpty()) return List.of();

        Set<String> emails = posts.stream()
                .map(Post::getAuthor)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        return posts.stream()
                .map(p -> convertToPostDto(p, userByEmail))
                .collect(Collectors.toList());
    }

    private void notifyGroupMembersOfNewPost(Post post) {
        Optional<Group> groupOpt = groupRepo.findByGroupId(post.getGroupId());
        if (groupOpt.isPresent()) {
            Group group = groupOpt.get();
            List<String> memberEmails = group.getMemberEmails();

            List<String> recipients = memberEmails.stream()
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
        } else {
            logger.warn("⚠️ Group with ID {} not found for FCM notification.", post.getGroupId());
        }
    }

    private PostDto convertToPostDto(Post post) {
        PostDto dto = new PostDto();
        dto.setId(post.getId());
        dto.setAuthor(post.getAuthor());
        dto.setContent(post.getContent());
        dto.setGroupId(String.valueOf(post.getGroupId()));
        dto.setGroupName(post.getGroupName());
        dto.setTimestamp(post.getTimestamp());       // createdAt
        dto.setEditedAt(post.getEditedAt());
        dto.setUpdatedAt(post.getUpdatedAt());       // 🔹 expose updatedAt for deltas

        if (post.getImage() != null) {
            String base64Image = java.util.Base64.getEncoder().encodeToString(post.getImage());
            dto.setBase64Image("data:image/jpeg;base64," + base64Image);
        }

        dto.setReactions(post.getReactions() != null ? post.getReactions() : new java.util.HashMap<>());
        dto.setTags(post.getTags() != null ? post.getTags() : new java.util.ArrayList<>());
        dto.setMentions(post.getMentions() != null ? post.getMentions() : new java.util.ArrayList<>());
        dto.setCommentsCount(post.getCommentsCount());

        userInfoRepo.findByUserEmail(post.getAuthor()).ifPresent(authorInfo -> {
            dto.setAuthorFirstName(authorInfo.getUserFirstName());
            dto.setAuthorLastName(authorInfo.getUserLastName());
            dto.setAuthorProfileImageURL(authorInfo.getProfileImageURL());
        });

        return dto;
    }

    private PostDto convertToPostDto(Post post, Map<String, UserInfo> userByEmail) {
        PostDto dto = new PostDto();
        dto.setId(post.getId());
        dto.setAuthor(post.getAuthor());
        dto.setContent(post.getContent());
        dto.setGroupId(String.valueOf(post.getGroupId()));
        dto.setGroupName(post.getGroupName());
        dto.setTimestamp(post.getTimestamp());       // createdAt
        dto.setEditedAt(post.getEditedAt());
        dto.setUpdatedAt(post.getUpdatedAt());       // 🔹 expose updatedAt for deltas

        if (post.getImage() != null) {
            String base64Image = java.util.Base64.getEncoder().encodeToString(post.getImage());
            dto.setBase64Image("data:image/jpeg;base64," + base64Image);
        }

        dto.setReactions(post.getReactions() != null ? post.getReactions() : new java.util.HashMap<>());
        dto.setTags(post.getTags() != null ? post.getTags() : new java.util.ArrayList<>());
        dto.setMentions(post.getMentions() != null ? post.getMentions() : new java.util.ArrayList<>());
        dto.setCommentsCount(post.getCommentsCount());

        UserInfo authorInfo = userByEmail.get(post.getAuthor());
        if (authorInfo != null) {
            dto.setAuthorFirstName(authorInfo.getUserFirstName());
            dto.setAuthorLastName(authorInfo.getUserLastName());
            dto.setAuthorProfileImageURL(authorInfo.getProfileImageURL());
        }

        return dto;
    }
}
