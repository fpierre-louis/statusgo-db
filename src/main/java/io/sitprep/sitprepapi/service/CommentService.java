package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.InMemoryImageResizer;
import io.sitprep.sitprepapi.domain.Comment;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.CommentDto;
import io.sitprep.sitprepapi.repo.CommentRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final GroupService groupService;
    private final GroupRepo groupRepo;
    private final WebSocketMessageSender webSocketMessageSender;

    @Autowired
    public CommentService(CommentRepo commentRepo, PostRepo postRepo, UserInfoRepo userInfoRepo,
                          NotificationService notificationService, GroupService groupService,
                          GroupRepo groupRepo,
                          WebSocketMessageSender webSocketMessageSender) {
        this.commentRepo = commentRepo;
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
        this.groupService = groupService;
        this.groupRepo = groupRepo;
        this.webSocketMessageSender = webSocketMessageSender;
    }

    public Comment createComment(Comment comment) {
        Comment savedComment = commentRepo.save(comment);
        CommentDto savedCommentDto = convertToCommentDto(savedComment);

        notifyPostAuthor(savedComment);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                webSocketMessageSender.sendNewComment(savedComment.getPostId(), savedCommentDto);
            }
        });

        return savedComment;
    }

    public Comment updateComment(Comment comment) {
        Comment updatedComment = commentRepo.save(comment);
        CommentDto updatedCommentDto = convertToCommentDto(updatedComment);

        notifyPostAuthor(updatedComment);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                webSocketMessageSender.sendNewComment(updatedComment.getPostId(), updatedCommentDto);
            }
        });

        return updatedComment;
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

    private void notifyPostAuthor(Comment comment) {
        Optional<Post> postOpt = postRepo.findById(comment.getPostId());
        if (postOpt.isEmpty()) return;

        Post post = postOpt.get();
        if (post.getAuthor().equalsIgnoreCase(comment.getAuthor())) {
            logger.debug("Comment author is also post author. Skipping self-notification.");
            return;
        }

        Optional<UserInfo> postAuthorOpt = userInfoRepo.findByUserEmail(post.getAuthor());
        Optional<UserInfo> commentAuthorOpt = userInfoRepo.findByUserEmail(comment.getAuthor());

        if (postAuthorOpt.isEmpty() || commentAuthorOpt.isEmpty()) return;

        UserInfo postAuthor = postAuthorOpt.get();
        UserInfo commentAuthor = commentAuthorOpt.get();

        String token = postAuthor.getFcmtoken();
        if (token == null || token.isEmpty()) return;

        String baseTargetUrl = groupRepo.findByGroupId(post.getGroupId())
                .map(groupService::getGroupTargetUrl)
                .orElse("/Linked/" + post.getGroupId());

        notificationService.sendNotification(
                commentAuthor.getUserFirstName() + " commented on your post",
                "\"" + comment.getContent() + "\"",
                commentAuthor.getUserFirstName(),
                commentAuthor.getProfileImageURL() != null
                        ? resizeImage(commentAuthor.getProfileImageURL())
                        : "/images/default-user-icon.png",
                Set.of(token),
                "comment_on_post",
                String.valueOf(post.getId()),
                baseTargetUrl + "?postId=" + post.getId(),
                null
        );

        logger.info("📣 Sent comment notification to post author '{}' for post ID {}", post.getAuthor(), post.getId());
    }

    private String resizeImage(String imageUrl) {
        try {
            byte[] resizedImageBytes = InMemoryImageResizer.resizeImageFromUrl(imageUrl, "png", 120, 120);
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(resizedImageBytes);
        } catch (IOException e) {
            logger.warn("⚠️ Failed to resize profile image: {}", e.getMessage());
            return "/images/default-user-icon.png";
        }
    }

    private CommentDto convertToCommentDto(Comment comment) {
        CommentDto dto = new CommentDto();
        dto.setId(comment.getId());
        dto.setPostId(comment.getPostId());
        dto.setAuthor(comment.getAuthor());
        dto.setContent(comment.getContent());
        dto.setTimestamp(comment.getTimestamp());

        userInfoRepo.findByUserEmail(comment.getAuthor()).ifPresent(authorInfo -> {
            dto.setAuthorFirstName(authorInfo.getUserFirstName());
            dto.setAuthorLastName(authorInfo.getUserLastName());
            dto.setAuthorProfileImageURL(authorInfo.getProfileImageURL());
        });

        return dto;
    }
}
