package io.sitprep.sitprepapi.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.dto.CommentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.regex.Pattern;

@Component
public class WebSocketMessageSender {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageSender.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public WebSocketMessageSender(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    // --- Helpers: build safe dot-style routing keys (no slashes, spaces, or '@') ---
    private static final Pattern NON_SAFE = Pattern.compile("[^A-Za-z0-9._-]");
    private static String key(Object... parts) {
        return Arrays.stream(parts)
                .map(String::valueOf)
                .map(s -> s == null ? "" : s)
                .map(s -> Normalizer.normalize(s, Normalizer.Form.NFKC))
                .map(s -> NON_SAFE.matcher(s).replaceAll("_"))
                .reduce((a, b) -> a + "." + b)
                .orElse("");
    }

    private void logPayload(String action, String destination, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            logger.info("🔔 {} to [{}]", action, destination);
            logger.info("📦 Payload:\n{}", json);
        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to serialize payload for {}: {}", destination, e.getMessage(), e);
        }
    }

    // ---- Group posts (broadcast) ----
    public void sendNewPost(String groupId, PostDto postDto) {
        String destination = "/topic/" + key("posts", groupId); // e.g., /topic/posts.123
        logPayload("Broadcasting POST", destination, postDto);
        messagingTemplate.convertAndSend(destination, postDto);
    }

    public void sendPostDeletion(String groupId, Long postId) {
        String destination = "/topic/" + key("posts", groupId, "delete"); // /topic/posts.123.delete
        logger.info("🗑️ Broadcasting DELETION to [{}] for post ID: {}", destination, postId);
        messagingTemplate.convertAndSend(destination, postId);
    }

    // ---- Comments (broadcast) ----
    public void sendNewComment(Long postId, CommentDto commentDto) {
        String destination = "/topic/" + key("comments", postId); // /topic/comments.456
        logPayload("Broadcasting COMMENT", destination, commentDto);
        messagingTemplate.convertAndSend(destination, commentDto);
    }

    public void sendCommentDeletion(Long postId, Long commentId) {
        String destination = "/topic/" + key("comments", postId, "delete"); // /topic/comments.456.delete
        logger.info("🗑️ Broadcasting COMMENT DELETION to [{}] for comment ID: {}", destination, commentId);
        messagingTemplate.convertAndSend(destination, commentId);
    }

    // ---- Generic broadcast ----
    public void sendGenericUpdate(String subtopic, Object payload) {
        String destination = "/topic/" + key(subtopic);
        logPayload("Broadcasting GENERIC update", destination, payload);
        messagingTemplate.convertAndSend(destination, payload);
    }

    // ---- Per-user notifications (no topic key with email; use user destination) ----
    public void sendInAppNotification(NotificationPayload payload) {
        logPayload("Sending in-app notification", "/user/queue/notifications", payload);
        messagingTemplate.convertAndSendToUser(
                payload.getRecipientEmail(),      // Principal name (email from JwtHandshakeHandler)
                "/queue/notifications",           // Client subscribes to /user/queue/notifications
                payload
        );
    }

    public void sendGroupAlertNotification(NotificationPayload payload) {
        logPayload("Sending group alert", "/user/queue/notifications", payload);
        messagingTemplate.convertAndSendToUser(
                payload.getRecipientEmail(),
                "/queue/notifications",
                payload
        );
    }
}
