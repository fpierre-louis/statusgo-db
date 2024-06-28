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
import java.util.stream.Collectors;

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
                            "Someone commented on your post in group " + post.getGroupId(),
                            Set.of(token)
                    );
                }
            }
        }
    }
}
