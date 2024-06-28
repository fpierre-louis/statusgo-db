package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PostService {
    private final PostRepo postRepo;
    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;

    @Autowired
    public PostService(PostRepo postRepo, GroupRepo groupRepo, UserInfoRepo userInfoRepo, NotificationService notificationService) {
        this.postRepo = postRepo;
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
    }

    public Post createPost(Post post) {
        Post savedPost = postRepo.save(post);
        notifyGroupMembers(savedPost);
        return savedPost;
    }

    public List<Post> getPostsByGroupId(Long groupId) {
        return postRepo.findByGroupId(groupId);
    }

    public Optional<Post> getPostById(Long id) {
        return postRepo.findById(id);
    }

    public void deletePost(Long id) {
        postRepo.deleteById(id);
    }

    public Post updatePost(Post post) {
        Post updatedPost = postRepo.save(post);
        notifyGroupMembers(updatedPost);
        return updatedPost;
    }

    private void notifyGroupMembers(Post post) {
        Optional<Group> groupOpt = groupRepo.findById(post.getGroupId());
        if (groupOpt.isPresent()) {
            Group group = groupOpt.get();
            List<String> memberEmails = group.getMemberEmails();
            List<UserInfo> users = userInfoRepo.findByUserEmailIn(memberEmails);
            Set<String> tokens = users.stream()
                    .map(UserInfo::getFcmtoken)
                    .filter(token -> token != null && !token.isEmpty())
                    .collect(Collectors.toSet());
            if (!tokens.isEmpty()) {
                notificationService.sendNotification(
                        "New Post in Your Group",
                        "There is a new post in your group " + group.getGroupName(),
                        tokens
                );
            }
        }
    }
}
