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
            byte[] imageBytes = Base64.getDecoder().decode(encodedImage);
            post.setImage(imageBytes);
        }

        Post savedPost = postRepo.save(post);
        notifyGroupMembersOfNewPost(savedPost);

        return convertToPostDto(savedPost);
    }

    @Transactional
    public Post updatePost(Post post, MultipartFile imageFile) throws IOException {
        String authenticatedUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();

        if (!post.getAuthor().equals(authenticatedUserEmail)) {
            throw new SecurityException("User not authorized to update this post.");
        }

        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes());
        }

        Post updatedPost = postRepo.save(post);
        PostDto updatedPostDto = convertToPostDto(updatedPost);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                webSocketMessageSender.sendNewPost(updatedPost.getGroupId(), updatedPostDto);
            }
        });

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
            @Override
            public void afterCommit() {
                webSocketMessageSender.sendNewPost(updated.getGroupId(), updatedDto);
            }
        });
    }

    public List<Post> getPostsByGroupId(String groupId) {
        return postRepo.findPostsByGroupId(groupId);
    }

    /** Backfill: posts since timestamp for a group */
    public List<PostDto> getPostsByGroupSince(String groupId, Instant since) {
        List<Post> rows = postRepo.findByGroupIdAndTimestampAfterOrderByTimestampAsc(groupId, since);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream()
                .map(Post::getAuthor)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        List<PostDto> out = new ArrayList<>(rows.size());
        for (Post p : rows) out.add(convertToPostDto(p, userByEmail));
        return out;
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

        return posts.stream().map(p -> convertToPostDto(p, userByEmail)).collect(Collectors.toList());
    }

    public Optional<Post> getPostById(Long id) {
        return postRepo.findById(id);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<PostDto> getPostDtoById(Long id) {
        return postRepo.findById(id).map(this::convertToPostDto);
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

    private void notifyGroupMembersOfNewPost(Post post) {
        groupRepo.findByGroupId(post.getGroupId()).ifPresentOrElse(group -> {
            List<String> recipientEmails = group.getMemberEmails() == null ? List.of() : group.getMemberEmails()
                    .stream()
                    .filter(email -> !email.equalsIgnoreCase(post.getAuthor()))
                    .toList();

            if (recipientEmails.isEmpty()) {
                logger.warn("No recipients found for group {}", group.getGroupName());
                return;
            }

            // Resolve author meta
            Optional<UserInfo> authorOpt = userInfoRepo.findByUserEmail(post.getAuthor());
            String authorFirst = authorOpt.map(UserInfo::getUserFirstName).orElse("Someone");
            String authorProfile = authorOpt.map(UserInfo::getProfileImageURL).orElse("/images/default-user-icon.png");

            String notificationTitle = group.getGroupName();
            String snippet = post.getContent() == null ? "" :
                    (post.getContent().length() > 50 ? post.getContent().substring(0, 50) + "..." : post.getContent());
            String notificationBody = String.format("%s posted in %s: '%s'",
                    authorFirst, group.getGroupName(), snippet);

            String baseTargetUrl = GroupUrlUtil.getGroupTargetUrl(group);
            String targetUrl = baseTargetUrl + "?postId=" + post.getId();

            // Fetch recipients with tokens
            List<UserInfo> users = userInfoRepo.findByUserEmailIn(recipientEmails);

            for (UserInfo user : users) {
                String token = user.getFcmtoken();
                if (token == null || token.isEmpty()) continue;

                notificationService.sendNotification(
                        notificationTitle,
                        notificationBody,
                        authorFirst,
                        authorProfile,
                        java.util.Set.of(token),
                        "post_notification",
                        post.getGroupId(),
                        targetUrl,
                        String.valueOf(post.getId()),
                        user.getUserEmail() // NEW
                );
            }

            logger.info("📣 Sent post push for group '{}' to {} members.", group.getGroupName(), users.size());
        }, () -> logger.warn("⚠️ Group with ID {} not found for FCM notification.", post.getGroupId()));
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
            String base64Image = java.util.Base64.getEncoder().encodeToString(post.getImage());
            dto.setBase64Image("data:image/jpeg;base64," + base64Image);
        }

        dto.setEditedAt(post.getEditedAt());
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
        dto.setTimestamp(post.getTimestamp());

        if (post.getImage() != null) {
            String base64Image = java.util.Base64.getEncoder().encodeToString(post.getImage());
            dto.setBase64Image("data:image/jpeg;base64," + base64Image);
        }

        dto.setEditedAt(post.getEditedAt());
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
