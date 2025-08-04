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

    public void sendNewPost(String groupId, PostDto postDto) {
        String destination = "/topic/posts/" + groupId;

        try {
            String payload = objectMapper.writeValueAsString(postDto);
            logger.info("🔔 Broadcasting POST to [{}]", destination);
            logger.info("📦 Payload:\n{}", payload);
            messagingTemplate.convertAndSend(destination, postDto);
        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to serialize PostDto for WebSocket broadcast: {}", e.getMessage(), e);
        }
    }

    public void sendPostDeletion(String groupId, Long postId) {
        String destination = "/topic/posts/" + groupId + "/delete";
        logger.info("🗑️ Broadcasting DELETION to [{}] for post ID: {}", destination, postId);
        messagingTemplate.convertAndSend(destination, postId);
    }

    public void sendNewComment(Long postId, CommentDto commentDto) {
        String destination = "/topic/comments/" + postId;

        try {
            String payload = objectMapper.writeValueAsString(commentDto);
            logger.info("💬 Broadcasting COMMENT to [{}]", destination);
            logger.info("📦 Payload:\n{}", payload);
            messagingTemplate.convertAndSend(destination, commentDto);
        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to serialize CommentDto for WebSocket broadcast: {}", e.getMessage(), e);
        }
    }

    public void sendGenericUpdate(String destination, Object payload) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            logger.info("🔁 Broadcasting GENERIC update to [{}]", destination);
            logger.info("📦 Payload:\n{}", jsonPayload);
            messagingTemplate.convertAndSend(destination, payload);
        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to serialize generic payload for destination {}: {}", destination, e.getMessage(), e);
        }
    }

    public void sendCommentDeletion(Long postId, Long commentId) {
        String destination = "/topic/comments/" + postId + "/delete";
        logger.info("🗑️ Broadcasting COMMENT DELETION to [{}] for comment ID: {}", destination, commentId);
        messagingTemplate.convertAndSend(destination, commentId);
    }

    public void sendInAppNotification(NotificationPayload payload) {
        String destination = "/topic/notifications/" + payload.getRecipientEmail();
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            logger.info("🔔 Sending in-app notification to [{}]", destination);
            messagingTemplate.convertAndSend(destination, payload);
        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to serialize NotificationPayload: {}", e.getMessage());
        }
    }

    public void sendGroupAlertNotification(NotificationPayload payload) {
        String destination = "/topic/notifications/" + payload.getRecipientEmail();
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            logger.info("🚨 Sending group alert to [{}]: {}", destination, jsonPayload);
            messagingTemplate.convertAndSend(destination, payload);
        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to send group alert WebSocket message: {}", e.getMessage());
        }
    }





}