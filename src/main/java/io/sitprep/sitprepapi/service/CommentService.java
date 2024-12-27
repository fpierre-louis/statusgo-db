package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Comment;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.CommentRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class CommentService {
    private final CommentRepo commentRepo;
    private final PostRepo postRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;

    @Autowired
    public CommentService(CommentRepo commentRepo, PostRepo postRepo, UserInfoRepo userInfoRepo, NotificationService notificationService) {
        this.commentRepo = commentRepo;
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
    }

    public Comment createComment(Comment comment) {
        Comment savedComment = commentRepo.save(comment);
        notifyPostAuthor(savedComment);
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
        return updatedComment;
    }

    private void notifyPostAuthor(Comment comment) {
        // Fetch the post associated with the comment
        Optional<Post> postOpt = postRepo.findById(comment.getPostId());
        if (postOpt.isPresent()) {
            Post post = postOpt.get();

            // Fetch the user who authored the post
            Optional<UserInfo> postAuthorOpt = userInfoRepo.findByUserEmail(post.getAuthor());
            if (postAuthorOpt.isPresent()) {
                UserInfo postAuthor = postAuthorOpt.get();

                // Fetch the comment author's profile
                Optional<UserInfo> commentAuthorOpt = userInfoRepo.findByUserEmail(comment.getAuthor());
                if (commentAuthorOpt.isPresent()) {
                    UserInfo commentAuthor = commentAuthorOpt.get();

                    // Prepare the notification details
                    String token = postAuthor.getFcmtoken();
                    if (token != null && !token.isEmpty()) {
                        String notificationTitle = commentAuthor.getUserFirstName() + " commented on your post";
                        String notificationBody = "\"" + comment.getContent() + "\"";
                        String commentAuthorImage = commentAuthor.getProfileImageURL() != null
                                ? commentAuthor.getProfileImageURL()
                                : "/images/default-profile.png"; // Fallback icon if profile image is missing

                        // Send the notification using the existing NotificationService
                        notificationService.sendNotification(
                                notificationTitle,
                                notificationBody,
                                commentAuthor.getUserFirstName(), // Pass comment author's name as "from"
                                Set.of(token),
                                "post_notification",
                                String.valueOf(post.getGroupId())
                        );
                    }
                }
            }
        }
    }
}
