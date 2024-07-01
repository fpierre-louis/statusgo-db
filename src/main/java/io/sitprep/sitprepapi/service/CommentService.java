package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Comment;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.CommentRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CommentService {
    private final CommentRepo commentRepo;
    private final PostRepo postRepo;
    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;

    @Autowired
    public CommentService(CommentRepo commentRepo, PostRepo postRepo, GroupRepo groupRepo, UserInfoRepo userInfoRepo, NotificationService notificationService) {
        this.commentRepo = commentRepo;
        this.postRepo = postRepo;
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
    }

    public Comment createComment(Comment comment) {
        Comment savedComment = commentRepo.save(comment);
        notifyPostAuthor(savedComment);
        notifyGroupMembersForComment(savedComment);
        return savedComment;
    }

    public List<Comment> getCommentsByPostId(Long postId) {
        return commentRepo.findByPostId(postId);
    }

    public Optional<Comment> getCommentById(Long id) {
        return commentRepo.findById(id);
    }

    public void deleteComment(Long id) {
        commentRepo.deleteById(id);
    }

    public Comment updateComment(Comment comment) {
        Comment updatedComment = commentRepo.save(comment);
        notifyPostAuthor(updatedComment);
        notifyGroupMembersForComment(updatedComment);
        return updatedComment;
    }

    private void notifyPostAuthor(Comment comment) {
        Optional<Post> postOpt = postRepo.findById(comment.getPostId());
        if (postOpt.isPresent()) {
            Post post = postOpt.get();
            Optional<UserInfo> userOpt = userInfoRepo.findByUserEmail(post.getAuthor());
            if (userOpt.isPresent()) {
                UserInfo user = userOpt.get();
                String token = user.getFcmtoken();
                if (token != null && !token.isEmpty()) {
                    notificationService.sendNotification(
                            "New Comment on Your Post",
                            "Someone commented on your post in group " + post.getGroupName(),
                            Set.of(token)
                    );
                }
            }
        }
    }

    private void notifyGroupMembersForComment(Comment comment) {
        Optional<Post> postOpt = postRepo.findById(comment.getPostId());
        if (postOpt.isPresent()) {
            Post post = postOpt.get();
            Optional<Group> groupOpt = groupRepo.findById(post.getGroupId());
            if (groupOpt.isPresent()) {
                Group group = groupOpt.get();
                List<String> memberEmails = group.getMemberEmails();
                List<UserInfo> members = userInfoRepo.findByUserEmailIn(memberEmails);

                for (UserInfo member : members) {
                    String token = member.getFcmtoken();
                    if (token != null && !token.isEmpty()) {
                        String notificationTitle = "Hi " + member.getUserFirstName();
                        String notificationBody = post.getAuthor() + " just posted in " + group.getGroupName() + ": " + post.getContent().substring(0, Math.min(50, post.getContent().length())) + "...";

                        notificationService.sendNotification(notificationTitle, notificationBody, Set.of(token));
                    }
                }
            }
        }
    }
}
