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

    // REST creation (multipart)
    @Transactional
    public PostDto createPostWithFile(PostDto postDto, MultipartFile imageFile, String actorEmail) throws IOException {
        // MVP: enforce only if actor provided
        if (actorEmail != null && !actorEmail.isBlank()) {
            if (!actorEmail.equalsIgnoreCase(postDto.getAuthor())) {
                throw new SecurityException("User not authorized to create a post for another user.");
            }
        }

        Post post = new Post();
        post.setAuthor(postDto.getAuthor());
        post.setContent(postDto.getContent());
        post.setGroupId(postDto.getGroupId());
        post.setGroupName(postDto.getGroupName());
        post.setTimestamp(Instant.now());
        post.setTags(postDto.getTags());
        post.setMentions(postDto.getMentions());

        if (imageFile != null && !imageFile.isEmpty()) {
            try {
                post.setImage(imageFile.getBytes());
            } catch (IOException e) {
                logger.error("Failed to read uploaded image bytes: {}", imageFile.getOriginalFilename(), e);
            }
        }

        Post savedPost = postRepo.save(post);
        PostDto savedDto = convertToPostDto(savedPost);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    notifyGroupMembersOfNewPost(savedPost);
                    webSocketMessageSender.sendNewPost(savedPost.getGroupId(), savedDto);
                } catch (Exception e) {
                    logger.error("Post-commit WS/notify error for post {}", savedPost.getId(), e);
                }
            }
        });

        return savedDto;
    }

    // WS/text-only creation
    @Transactional
    public PostDto createPostFromDto(PostDto postDto, String actorEmail) throws IOException {
        if (actorEmail != null && !actorEmail.isBlank()) {
            if (!postDto.getAuthor().equalsIgnoreCase(actorEmail)) {
                throw new SecurityException("User not authorized to create a post for another user.");
            }
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
            String[] parts = postDto.getBase64Image().split(",", 2);
            String encodedImage = parts.length == 2 ? parts[1] : parts[0];
            byte[] imageBytes = Base64.getDecoder().decode(encodedImage);
            post.setImage(imageBytes);
        }

        Post savedPost = postRepo.save(post);
        PostDto savedDto = convertToPostDto(savedPost);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    notifyGroupMembersOfNewPost(savedPost);
                    webSocketMessageSender.sendNewPost(savedPost.getGroupId(), savedDto);
                } catch (Exception e) {
                    logger.error("Post-commit WS/notify error for post {}", savedPost.getId(), e);
                }
            }
        });

        return savedDto;
    }

    // REST update (multipart) — actorEmail provided by controller; no SecurityContext
    @Transactional
    public Post updatePost(Post post, MultipartFile imageFile, String actorEmail) throws IOException {
        if (actorEmail != null && !"anonymous".equalsIgnoreCase(actorEmail)) {
            if (post.getAuthor() == null || !post.getAuthor().equalsIgnoreCase(actorEmail)) {
                throw new SecurityException("User not authorized to update this post.");
            }
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

        return updatedPost;
    }

    @Transactional
    public void deletePostAndBroadcast(Long postId, String actorEmail) {
        Post post = postRepo.findById(postId).orElseThrow(() -> new RuntimeException("Post not found"));

        if (actorEmail != null && !"anonymous".equalsIgnoreCase(actorEmail)) {
            if (post.getAuthor() == null || !post.getAuthor().equalsIgnoreCase(actorEmail)) {
                throw new SecurityException("User not authorized to delete this post.");
            }
        }

        postRepo.delete(post);
        webSocketMessageSender.sendPostDeletion(post.getGroupId(), post.getId());
    }

    @Transactional
    public void updatePostFromDto(PostDto dto) {
        String actorEmail = dto.getAuthor(); // WS path provides author in dto

        Post post = postRepo.findById(dto.getId()).orElseThrow(() ->
                new IllegalArgumentException("Post not found for update: " + dto.getId()));

        if (actorEmail != null && !post.getAuthor().equalsIgnoreCase(actorEmail)) {
            throw new SecurityException("Not allowed to edit this post.");
        }

        post.setContent(dto.getContent());
        post.setEditedAt(Instant.now());
        post.setTags(dto.getTags());
        post.setMentions(dto.getMentions());

        if (dto.getBase64Image() != null && !dto.getBase64Image().isEmpty()) {
            String[] parts = dto.getBase64Image().split(",", 2);
            String encodedImage = parts.length == 2 ? parts[1] : parts[0];
            post.setImage(Base64.getDecoder().decode(encodedImage));
        } else if (dto.getBase64Image() == null && post.getImage() != null) {
            post.setImage(null);
        }

        Post updated = postRepo.save(post);
        PostDto updatedDto = convertToPostDto(updated);
        updatedDto.setTempId(dto.getTempId());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                webSocketMessageSender.sendNewPost(updated.getGroupId(), updatedDto);
            }
        });
    }

    public List<Post> getPostsByGroupId(String groupId) { return postRepo.findPostsByGroupId(groupId); }

    public List<PostDto> getPostsByGroupSince(String groupId, Instant since) {
        List<Post> rows = postRepo.findByGroupIdAndUpdatedAtAfterOrderByUpdatedAtAsc(groupId, since);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream().map(Post::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        List<PostDto> out = new ArrayList<>(rows.size());
        for (Post p : rows) out.add(convertToPostDto(p, userByEmail));
        return out;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<PostDto> getPostsByGroupIdDto(String groupId) {
        List<Post> posts = postRepo.findPostsByGroupId(groupId);
        if (posts.isEmpty()) return List.of();

        Set<String> emails = posts.stream().map(Post::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        return posts.stream().map(p -> convertToPostDto(p, userByEmail)).toList();
    }

    public Optional<Post> getPostById(Long id) { return postRepo.findById(id); }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<PostDto> getPostDtoById(Long id) { return postRepo.findById(id).map(this::convertToPostDto); }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<String, PostSummaryDto> getLatestPostsForGroups(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return Collections.emptyMap();

        List<Post> candidates = postRepo.findLatestPostsByGroupIds(groupIds);
        Map<String, Post> bestByGroup = new HashMap<>();
        for (Post p : candidates) {
            Post cur = bestByGroup.get(p.getGroupId());
            if (cur == null || p.getTimestamp().isAfter(cur.getTimestamp())
                    || (p.getTimestamp().equals(cur.getTimestamp()) && p.getId() > cur.getId())) {
                bestByGroup.put(p.getGroupId(), p);
            }
        }

        Set<String> emails = bestByGroup.values().stream().map(Post::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
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

    private void notifyGroupMembersOfNewPost(Post post) {
        groupRepo.findByGroupId(post.getGroupId()).ifPresentOrElse(group -> {
            var recipientEmails = group.getMemberEmails() == null ? List.<String>of() :
                    group.getMemberEmails().stream().filter(e -> !e.equalsIgnoreCase(post.getAuthor())).toList();

            if (recipientEmails.isEmpty()) {
                logger.warn("No recipients for group {}", group.getGroupName());
                return;
            }

            var authorOpt = userInfoRepo.findByUserEmail(post.getAuthor());
            String authorFirst = authorOpt.map(UserInfo::getUserFirstName).orElse("Someone");
            String authorProfile = authorOpt.map(UserInfo::getProfileImageURL).orElse("/images/default-user-icon.png");

            String title = group.getGroupName();
            String snippet = post.getContent() == null ? "" :
                    (post.getContent().length() > 50 ? post.getContent().substring(0, 50) + "..." : post.getContent());
            String body = String.format("%s posted in %s: '%s'", authorFirst, group.getGroupName(), snippet);

            String baseTargetUrl = GroupUrlUtil.getGroupTargetUrl(group);
            String targetUrl = baseTargetUrl + "?postId=" + post.getId();

            List<UserInfo> users = userInfoRepo.findByUserEmailIn(recipientEmails);
            for (UserInfo user : users) {
                notificationService.deliverPresenceAware(
                        user.getUserEmail(), title, body, authorFirst, authorProfile,
                        "post_notification", post.getGroupId(), targetUrl, String.valueOf(post.getId()),
                        user.getFcmtoken()
                );
            }

            logger.info("📣 Post notification sent for '{}' to {} members.", group.getGroupName(), users.size());
        }, () -> logger.warn("⚠️ Group with ID {} not found (notify)", post.getGroupId()));
    }

    private PostDto convertToPostDto(Post post) {
        PostDto dto = new PostDto();
        dto.setId(post.getId());
        dto.setAuthor(post.getAuthor());
        dto.setContent(post.getContent());
        dto.setGroupId(String.valueOf(post.getGroupId()));
        dto.setGroupName(post.getGroupName());
        dto.setTimestamp(post.getTimestamp());
        dto.setEditedAt(post.getEditedAt());
        dto.setUpdatedAt(post.getUpdatedAt());
        if (post.getImage() != null) {
            String b64 = java.util.Base64.getEncoder().encodeToString(post.getImage());
            dto.setBase64Image("data:image/jpeg;base64," + b64);
        }
        dto.setReactions(post.getReactions() != null ? post.getReactions() : new HashMap<>());
        dto.setTags(post.getTags() != null ? post.getTags() : new ArrayList<>());
        dto.setMentions(post.getMentions() != null ? post.getMentions() : new ArrayList<>());
        dto.setCommentsCount(post.getCommentsCount());

        userInfoRepo.findByUserEmail(post.getAuthor()).ifPresent(u -> {
            dto.setAuthorFirstName(u.getUserFirstName());
            dto.setAuthorLastName(u.getUserLastName());
            dto.setAuthorProfileImageURL(u.getProfileImageURL());
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
        dto.setTimestamp(post.getTimestamp());
        dto.setEditedAt(post.getEditedAt());
        dto.setUpdatedAt(post.getUpdatedAt());
        if (post.getImage() != null) {
            String b64 = java.util.Base64.getEncoder().encodeToString(post.getImage());
            dto.setBase64Image("data:image/jpeg;base64," + b64);
        }
        dto.setReactions(post.getReactions() != null ? post.getReactions() : new HashMap<>());
        dto.setTags(post.getTags() != null ? post.getTags() : new ArrayList<>());
        dto.setMentions(post.getMentions() != null ? post.getMentions() : new ArrayList<>());
        dto.setCommentsCount(post.getCommentsCount());

        UserInfo u = userByEmail.get(post.getAuthor());
        if (u != null) {
            dto.setAuthorFirstName(u.getUserFirstName());
            dto.setAuthorLastName(u.getUserLastName());
            dto.setAuthorProfileImageURL(u.getProfileImageURL());
        }
        return dto;
    }
}