package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PostDto; // ✅ Import PostDto
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // Original createPost method - now includes DTO conversion and WebSocket send
    @Transactional
    public Post createPost(Post post, MultipartFile imageFile) throws IOException {
        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes());
        }
        Post savedPost = postRepo.save(post);

        // Convert to DTO for WebSocket broadcast
        PostDto savedPostDto = convertToPostDto(savedPost); // ✅ Call conversion here

        // Trigger FCM notification (for background/offline users)
        notifyGroupMembersOfNewPost(savedPost); // FCM still uses the original Post entity data

        // Trigger WebSocket message (for real-time update to active users)
        webSocketMessageSender.sendNewPost(savedPost.getGroupId(), savedPostDto); // ✅ Send DTO

        return savedPost; // Return entity for REST API consistency
    }

    // Original updatePost method - now includes DTO conversion and WebSocket send
    @Transactional
    public Post updatePost(Post post, MultipartFile imageFile) throws IOException {
        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes());
        } else {
            // Check if there was an existing image and it should be removed (if imageFile is null and not just empty)
            // This logic depends on your frontend's update behavior for images.
            // For now, assuming null means 'no change' or 'keep existing' unless explicitly cleared.
            // If the frontend sends null to explicitly clear, you'd load the existing post first.
        }
        Post updatedPost = postRepo.save(post);

        // Convert to DTO for WebSocket broadcast
        PostDto updatedPostDto = convertToPostDto(updatedPost); // ✅ Call conversion here

        // Trigger WebSocket message for updated post
        webSocketMessageSender.sendNewPost(updatedPost.getGroupId(), updatedPostDto); // ✅ Send DTO

        return updatedPost; // Return entity for REST API consistency
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
                logger.warn("No FCM tokens found for group members to notify about new post in group: {}", group.getGroupName());
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
            logger.info("Sent new post notification for group {} to {} members.", group.getGroupName(), tokens.size());
        } else {
            logger.warn("Group with ID {} not found for new post notification.", post.getGroupId());
        }
    }

    // ✅ FIX: Ensure this DTO conversion method exists and is correctly implemented.
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