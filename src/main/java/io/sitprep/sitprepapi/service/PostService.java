// src/main/java/io/sitprep/sitprepapi/service/PostService.java
package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender; // ✅ Import WebSocketMessageSender
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

    @Autowired
    private PostRepo postRepo;

    @Autowired
    private UserInfoRepo userInfoRepo;

    @Autowired
    private GroupRepo groupRepo;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private GroupService groupService; // For using getGroupTargetUrl

    // ✅ NEW: Inject WebSocketMessageSender
    private final WebSocketMessageSender webSocketMessageSender;

    @Autowired // Modify constructor to inject WebSocketMessageSender
    public PostService(PostRepo postRepo, UserInfoRepo userInfoRepo, GroupRepo groupRepo,
                       NotificationService notificationService, GroupService groupService,
                       WebSocketMessageSender webSocketMessageSender) { // ✅ Add WebSocketMessageSender
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
        this.notificationService = notificationService;
        this.groupService = groupService;
        this.webSocketMessageSender = webSocketMessageSender; // ✅ Assign
    }


    @Transactional
    public Post createPost(Post post, MultipartFile imageFile) throws IOException {
        // Ensure base64Image is populated for the WebSocket message before saving
        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes()); // Save image as byte array
        }
        Post savedPost = postRepo.save(post);

        // Trigger FCM notification (for background/offline users)
        notifyGroupMembersOfNewPost(savedPost);

        // ✅ NEW: Trigger WebSocket message (for real-time update to active users)
        webSocketMessageSender.sendNewPost(savedPost.getGroupId(), savedPost);

        return savedPost;
    }

    @Transactional
    public Post updatePost(Post post, MultipartFile imageFile) throws IOException {
        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes()); // Update image if provided
        } else {
            post.setImage(null); // Explicitly remove image if none sent
        }
        Post updatedPost = postRepo.save(post);

        // ✅ NEW: Trigger WebSocket message for updated post
        webSocketMessageSender.sendNewPost(updatedPost.getGroupId(), updatedPost);

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
        // You might want to send a WebSocket message for post deletion too if UI needs to react
        // webSocketMessageSender.sendGenericUpdate("/topic/posts/deleted", id);
    }

    // Add or update reaction
    @Transactional
    public void addReaction(Post post, String reaction) {
        post.getReactions().put(reaction, post.getReactions().getOrDefault(reaction, 0) + 1);
        Post updatedPost = postRepo.save(post);
        // You might want to send a more granular WebSocket message for reactions
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
            String notificationBody = String.format("%s posted in %s: '%s'", //
                    authorFirstName, //
                    post.getGroupName(), //
                    post.getContent().length() > 50 ? post.getContent().substring(0, 50) + "..." : post.getContent()); //

            String baseTargetUrl = groupService.getGroupTargetUrl(group);

            notificationService.sendNotification(
                    notificationTitle, //
                    notificationBody, //
                    authorFirstName, //
                    authorProfileImageUrl, //
                    tokens, //
                    "post_notification", //
                    post.getGroupId(), // referenceId is group ID
                    baseTargetUrl + "?postId=" + post.getId(), //
                    String.valueOf(post.getId()) // additionalData for post ID to link directly
            );
            logger.info("Sent new post notification for group {} to {} members.", group.getGroupName(), tokens.size());
        } else {
            logger.warn("Group with ID {} not found for new post notification.", post.getGroupId());
        }
    }
}