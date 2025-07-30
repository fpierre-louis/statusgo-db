package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.InMemoryImageResizer;
import io.sitprep.sitprepapi.domain.Comment;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.CommentRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class CommentService {
    private static final Logger logger = LoggerFactory.getLogger(CommentService.class);
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
        Optional<Post> postOpt = postRepo.findById(comment.getPostId());
        if (postOpt.isPresent()) {
            Post post = postOpt.get();
            // Only notify if the comment author is NOT the post author
            if (!post.getAuthor().equalsIgnoreCase(comment.getAuthor())) {
                Optional<UserInfo> postAuthorOpt = userInfoRepo.findByUserEmail(post.getAuthor());
                if (postAuthorOpt.isPresent()) {
                    UserInfo postAuthor = postAuthorOpt.get();
                    Optional<UserInfo> commentAuthorOpt = userInfoRepo.findByUserEmail(comment.getAuthor());
                    if (commentAuthorOpt.isPresent()) {
                        UserInfo commentAuthor = commentAuthorOpt.get();

                        String token = postAuthor.getFcmtoken(); //
                        if (token != null && !token.isEmpty()) { //
                            notificationService.sendNotification(
                                    commentAuthor.getUserFirstName() + " commented on your post", //
                                    "\"" + comment.getContent() + "\"", //
                                    commentAuthor.getUserFirstName(), //
                                    commentAuthor.getProfileImageURL() != null //
                                            ? resizeImage(commentAuthor.getProfileImageURL()) //
                                            : "/images/default-user-icon.png", //
                                    Set.of(token), //
                                    "comment_on_post", //
                                    String.valueOf(post.getId()), //
                                    "/Linked/" + post.getGroupId() + "?postId=" + post.getId(), //
                                    null //
                            );
                        }
                    }
                }
            } else {
                logger.debug("Comment author is also post author. Skipping notification for post author.");
            }
        }
    }


    private String resizeImage(String imageUrl) {
        try {
            byte[] resizedImageBytes = InMemoryImageResizer.resizeImageFromUrl(imageUrl, "png", 120, 120);
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(resizedImageBytes);
        } catch (IOException e) {
            logger.warn("Failed to resize image: {}", e.getMessage());
            return "/images/default-user-icon.png"; // Fallback to default icon if resizing fails
        }
    }
}