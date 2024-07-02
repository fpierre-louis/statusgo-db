package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PostService {

    @Autowired
    private PostRepo postRepo;

    @Autowired
    private GroupRepo groupRepo;

    @Autowired
    private UserInfoRepo userInfoRepo;

    @Autowired
    private NotificationService notificationService;

    @Transactional
    public Post createPost(Post post) {
        Post savedPost = postRepo.save(post);
        notifyGroupMembers(savedPost);
        return savedPost;
    }

    public List<Post> getPostsByGroupId(Long groupId) {
        return postRepo.findByGroupId(groupId);
    }

    public void deletePost(Long id) {
        postRepo.deleteById(id);
    }

    public Post updatePost(Post post) {
        return postRepo.save(post);
    }

    public Optional<Post> getPostById(Long id) {
        return postRepo.findById(id);
    }

    private void notifyGroupMembers(Post post) {
        Group group = groupRepo.findById(post.getGroupId())
                .orElseThrow(() -> new RuntimeException("Group not found for this id :: " + post.getGroupId()));

        List<String> memberEmails = group.getMemberEmails();
        List<UserInfo> users = userInfoRepo.findByUserEmailIn(memberEmails);

        Set<String> tokens = users.stream()
                .map(UserInfo::getFcmtoken)
                .filter(token -> token != null && !token.isEmpty())
                .collect(Collectors.toSet());

        if (tokens.isEmpty()) {
            System.out.println("No FCM tokens found for group members.");
            return;
        }

        // Fetch the author's first name
        UserInfo authorInfo = userInfoRepo.findByUserEmail(post.getAuthor())
                .orElseThrow(() -> new RuntimeException("Author not found for this email: " + post.getAuthor()));

        String authorFirstName = authorInfo.getUserFirstName();

        String notificationTitle = "Hi " + users.get(0).getUserFirstName(); // Using the first member's name for simplicity
        String notificationBody = authorFirstName + " posted in " + group.getGroupName() + ": \"" + post.getContent().substring(0, Math.min(post.getContent().length(), 100)) + "\"...";

        try {
            notificationService.sendNotification(notificationTitle, notificationBody, authorFirstName, tokens);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
