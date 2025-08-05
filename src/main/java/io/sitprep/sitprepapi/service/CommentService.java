package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.GroupUrlUtil;
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
import java.util.stream.Collectors;

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
                          GroupRepo groupRepo, WebSocketMessageSender webSocketMessageSender) {
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

        notifyRelevantUsers(savedComment, null);

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

        notifyRelevantUsers(updatedComment, null);

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

    public void createCommentFromDto(CommentDto dto) {
        Comment comment = new Comment();
        comment.setPostId(dto.getPostId());
        comment.setAuthor(dto.getAuthor());
        comment.setContent(dto.getContent());
        comment.setTimestamp(dto.getTimestamp() != null ? dto.getTimestamp() : java.time.Instant.now());

        Comment saved = commentRepo.save(comment);

        CommentDto result = convertToCommentDto(saved);
        result.setTempId(dto.getTempId());

        notifyRelevantUsers(saved, dto.getTempId());

        webSocketMessageSender.sendNewComment(saved.getPostId(), result);
    }

    public void updateCommentFromDto(CommentDto dto) {
        Comment comment = commentRepo.findById(dto.getId()).orElse(null);
        if (comment == null) return;

        comment.setContent(dto.getContent());
        comment.setTimestamp(dto.getTimestamp());
        comment.setAuthor(dto.getAuthor());
        commentRepo.save(comment);

        webSocketMessageSender.sendNewComment(dto.getPostId(), convertToCommentDto(comment));
    }

    public void deleteCommentAndBroadcast(Long commentId, Long postId) {
        commentRepo.deleteById(commentId);
        webSocketMessageSender.sendCommentDeletion(postId, commentId);
    }

    private CommentDto convertToCommentDto(Comment comment) {
        CommentDto dto = new CommentDto();
        dto.setId(comment.getId());
        dto.setPostId(comment.getPostId());
        dto.setAuthor(comment.getAuthor());
        dto.setContent(comment.getContent());
        dto.setTimestamp(comment.getTimestamp());
        dto.setEdited(false);

        userInfoRepo.findByUserEmail(comment.getAuthor()).ifPresent(authorInfo -> {
            dto.setAuthorFirstName(authorInfo.getUserFirstName());
            dto.setAuthorLastName(authorInfo.getUserLastName());
            dto.setAuthorProfileImageURL(authorInfo.getProfileImageURL());
        });

        return dto;
    }

    private void notifyRelevantUsers(Comment savedComment, String tempId) {
        Optional<Post> postOpt = postRepo.findById(savedComment.getPostId());
        if (postOpt.isEmpty()) return;

        Post post = postOpt.get();
        Long postId = post.getId();

        if (!post.getAuthor().equalsIgnoreCase(savedComment.getAuthor())) {
            notifyUser(post.getAuthor(), savedComment, "comment_on_post", postId, "commented on your post");
        }

        List<Comment> allComments = commentRepo.findByPostId(postId);
        Set<String> uniqueCommenters = allComments.stream()
                .map(Comment::getAuthor)
                .filter(email -> !email.equalsIgnoreCase(post.getAuthor()))
                .filter(email -> !email.equalsIgnoreCase(savedComment.getAuthor()))
                .collect(Collectors.toSet());

        for (String commenter : uniqueCommenters) {
            notifyUser(commenter, savedComment, "comment_thread_reply", postId, "also replied to a post you commented on");
        }
    }

    private void notifyUser(String recipientEmail, Comment triggeringComment, String type, Long postId, String actionLabel) {
        Optional<UserInfo> recipientOpt = userInfoRepo.findByUserEmail(recipientEmail);
        Optional<UserInfo> authorOpt = userInfoRepo.findByUserEmail(triggeringComment.getAuthor());

        if (recipientOpt.isEmpty() || authorOpt.isEmpty()) return;

        UserInfo recipient = recipientOpt.get();
        UserInfo author = authorOpt.get();

        String token = recipient.getFcmtoken();
        if (token == null || token.isEmpty()) return;

        String groupUrl = groupRepo.findByGroupId(postRepo.findById(postId).map(Post::getGroupId).orElse(null))
                .map(GroupUrlUtil::getGroupTargetUrl)
                .orElse("/Linked/" + postId);

        notificationService.sendNotification(
                author.getUserFirstName() + " " + actionLabel,
                "\"" + triggeringComment.getContent() + "\"",
                author.getUserFirstName(),
                author.getProfileImageURL() != null
                        ? resizeImage(author.getProfileImageURL())
                        : "/images/default-user-icon.png",
                Set.of(token),
                type,
                String.valueOf(postId),
                groupUrl + "?postId=" + postId,
                null
        );

        logger.info("📨 Sent '{}' notification to '{}' for post {}", type, recipientEmail, postId);
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
}
