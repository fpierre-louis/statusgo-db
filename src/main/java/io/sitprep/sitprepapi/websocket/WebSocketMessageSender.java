package io.sitprep.sitprepapi.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.dto.CommentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

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

    /**
     * Sends a new post message to all subscribers of /topic/posts/{groupId}
     */
    public void sendNewPost(String groupId, PostDto postDto) {
        String destination = "/topic/posts/" + groupId;
        try {
            String payload = objectMapper.writeValueAsString(postDto);
            messagingTemplate.convertAndSend(destination, payload);
            logger.info("🚀 WebSocket Broadcast: Sent to {} with payload: {}", destination, payload);
        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to serialize PostDto for WebSocket broadcast", e);
        }
    }

    /**
     * Sends a new comment to /topic/comments/{postId}
     */
    public void sendNewComment(Long postId, CommentDto commentDto) {
        String destination = "/topic/comments/" + postId;
        try {
            String payload = objectMapper.writeValueAsString(commentDto);
            messagingTemplate.convertAndSend(destination, payload);
            logger.info("💬 WebSocket Comment Broadcast: Sent to {} with payload: {}", destination, payload);
        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to serialize CommentDto for WebSocket broadcast", e);
        }
    }

    /**
     * Sends any object as a generic update to a custom topic
     */
    public void sendGenericUpdate(String destination, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            messagingTemplate.convertAndSend(destination, json);
            logger.info("📢 WebSocket Generic Update: Sent to {} with payload: {}", destination, json);
        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to serialize generic WebSocket payload", e);
        }
    }
}
