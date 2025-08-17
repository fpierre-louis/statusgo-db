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

    public void sendNewPost(String groupId, PostDto postDto) {
        String destination = "/topic/" + key("posts", groupId);
        logPayload("Broadcasting POST", destination, postDto);
        messagingTemplate.convertAndSend(destination, postDto);
    }

    public void sendPostDeletion(String groupId, Long postId) {
        String destination = "/topic/" + key("posts", groupId, "delete");
        logger.info("🗑️ Broadcasting DELETION to [{}] for post ID: {}", destination, postId);
        messagingTemplate.convertAndSend(destination, postId);
    }

    public void sendNewComment(Long postId, CommentDto commentDto) {
        String destination = "/topic/" + key("comments", postId);
        logPayload("Broadcasting COMMENT", destination, commentDto);
        messagingTemplate.convertAndSend(destination, commentDto);
    }

    public void sendCommentDeletion(Long postId, Long commentId) {
        String destination = "/topic/" + key("comments", postId, "delete");
        logger.info("🗑️ Broadcasting COMMENT DELETION to [{}] for comment ID: {}", destination, commentId);
        messagingTemplate.convertAndSend(destination, commentId);
    }

    public void sendGenericUpdate(String subtopic, Object payload) {
        String destination = "/topic/" + key(subtopic);
        logPayload("Broadcasting GENERIC update", destination, payload);
        messagingTemplate.convertAndSend(destination, payload);
    }

    // ✅ CORRECTED: Use convertAndSendToUser for direct, efficient messaging
    public void sendInAppNotification(NotificationPayload payload) {
        String userDestination = "/queue/notifications"; // The logical destination the client subscribes to
        String recipientEmail = payload.getRecipientEmail(); // The user's principal name (their email)

        logPayload("Sending in-app notification to user " + recipientEmail, "/user" + userDestination, payload);

        messagingTemplate.convertAndSendToUser(
                recipientEmail,
                userDestination,
                payload
        );
    }

    // ✅ CORRECTED: Use convertAndSendToUser for direct, efficient messaging
    public void sendGroupAlertNotification(NotificationPayload payload) {
        String userDestination = "/queue/notifications";
        String recipientEmail = payload.getRecipientEmail();

        logPayload("Sending group alert to user " + recipientEmail, "/user" + userDestination, payload);

        messagingTemplate.convertAndSendToUser(
                recipientEmail,
                userDestination,
                payload
        );
    }
}