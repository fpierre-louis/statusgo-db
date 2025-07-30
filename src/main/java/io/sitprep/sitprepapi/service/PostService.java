package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo; // Import GroupRepo
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo; // Import UserInfoRepo
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set; // Import Set
import java.util.stream.Collectors; // Import Collectors

@Service
public class PostService {

    private static final Logger logger = LoggerFactory.getLogger(PostService.class); // Add logger

    @Autowired
    private PostRepo postRepo;

    @Autowired // Inject UserInfoRepo
    private UserInfoRepo userInfoRepo;

    @Autowired // Inject GroupRepo
    private GroupRepo groupRepo;

    @Autowired // Inject NotificationService
    private NotificationService notificationService;

    @Transactional
    public Post createPost(Post post, MultipartFile imageFile) throws IOException {
        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes()); // Save image as byte array
        }
        Post savedPost = postRepo.save(post);
        notifyGroupMembersOfNewPost(savedPost); // Call new notification method
        return savedPost;
    }

    @Transactional
    public Post updatePost(Post post, MultipartFile imageFile) throws IOException {
        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes()); // Update image if provided
        } else {
            post.setImage(null); // Explicitly remove image if none sent
        }
        return postRepo.save(post);
    }


    public List<Post> getPostsByGroupId(String groupId) {  // Change Long to String
        return postRepo.findPostsByGroupId(groupId);
    }

    public Optional<Post> getPostById(Long id) {
        return postRepo.findById(id);
    }

    public void deletePost(Long id) {
        postRepo.deleteById(id);
    }

    // Add or update reaction
    @Transactional
    public void addReaction(Post post, String reaction) {
        post.getReactions().put(reaction, post.getReactions().getOrDefault(reaction, 0) + 1);
        postRepo.save(post);
    }

    private void notifyGroupMembersOfNewPost(Post post) {
        Optional<Group> groupOpt = groupRepo.findByGroupId(post.getGroupId());
        if (groupOpt.isPresent()) {
            Group group = groupOpt.get();
            List<String> memberEmails = group.getMemberEmails();

            // Exclude the post author from receiving a notification about their own post
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

            notificationService.sendNotification(
                    notificationTitle,
                    notificationBody,
                    authorFirstName,
                    authorProfileImageUrl,
                    tokens,
                    "post_notification",
                    post.getGroupId(), // referenceId is group ID
                    "/Linked/" + post.getGroupId(), // Target URL for the group's posts feed
                    String.valueOf(post.getId()) // additionalData for post ID to link directly
            );
            logger.info("Sent new post notification for group {} to {} members.", group.getGroupName(), tokens.size());
        } else {
            logger.warn("Group with ID {} not found for new post notification.", post.getGroupId());
        }
    }
}