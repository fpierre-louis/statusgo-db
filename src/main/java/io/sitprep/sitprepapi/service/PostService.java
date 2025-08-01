package io.sitprep.sitprepapi.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    public Post createPost(Post post, MultipartFile imageFile) throws IOException {
        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes());
        }
        Post savedPost = postRepo.save(post);

        try {
            PostDto savedPostDto = convertToPostDto(savedPost);
            notifyGroupMembersOfNewPost(savedPost);

            // ✅ WebSocket broadcast after commit
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    webSocketMessageSender.sendNewPost(savedPost.getGroupId(), savedPostDto);
                }
            });

            logger.info("🚀 Successfully processed and scheduled broadcast for post ID: {}", savedPost.getId());
        } catch (Exception e) {
            logger.error("❌ Failed to process post ID {}: {}", savedPost.getId(), e.getMessage(), e);
            throw new RuntimeException("Failed to create post due to an internal server error.", e);
        }

        return savedPost;
    }

    @Transactional
    public Post updatePost(Post post, MultipartFile imageFile) throws IOException {
        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes());
        }
        Post updatedPost = postRepo.save(post);

        try {
            PostDto updatedPostDto = convertToPostDto(updatedPost);

            // ✅ WebSocket broadcast after commit
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    webSocketMessageSender.sendNewPost(updatedPost.getGroupId(), updatedPostDto);
                }
            });

            logger.info("🚀 Successfully processed and scheduled broadcast for updated post ID: {}", updatedPost.getId());
        } catch (Exception e) {
            logger.error("❌ Failed to update post ID {}: {}", updatedPost.getId(), e.getMessage(), e);
            throw new RuntimeException("Failed to update post due to an internal server error.", e);
        }

        return updatedPost;
    }

    public List<Post> getPostsByGroupId(String groupId) {
        return postRepo.findPostsByGroupId(groupId);
    }

    public Optional<Post> getPostById(Long id) {
        return postRepo.findById(id);
    }

    public void deletePost(Long id) {
        postRepo.deleteById(id);
    }

    @Transactional
    public void addReaction(Post post, String reaction) {
        post.getReactions().put(reaction, post.getReactions().getOrDefault(reaction, 0) + 1);
        Post updatedPost = postRepo.save(post);
        webSocketMessageSender.sendGenericUpdate("/topic/posts/" + updatedPost.getGroupId() + "/reactions/" + updatedPost.getId(), updatedPost.getReactions());
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

            String baseTargetUrl = groupService.getGroupTargetUrl(group);

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
        dto.setGroupId(post.getGroupId());
        dto.setGroupName(post.getGroupName());
        dto.setTimestamp(post.getTimestamp());
        dto.setBase64Image(post.getBase64Image());
        dto.setReactions(post.getReactions());
        dto.setEditedAt(post.getEditedAt());
        dto.setTags(post.getTags());
        dto.setCommentsCount(post.getCommentsCount());
        dto.setMentions(post.getMentions());

        userInfoRepo.findByUserEmail(post.getAuthor()).ifPresent(authorInfo -> {
            dto.setAuthorFirstName(authorInfo.getUserFirstName());
            dto.setAuthorLastName(authorInfo.getUserLastName());
            dto.setAuthorProfileImageURL(authorInfo.getProfileImageURL());
        });

        return dto;
    }
}
