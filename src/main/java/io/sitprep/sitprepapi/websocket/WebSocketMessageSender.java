// src/main/java/io.sitprep.sitprepapi/websocket/WebSocketMessageSender.java
package io.sitprep.sitprepapi.websocket;

import com.fasterxml.jackson.core.JsonProcessingException; //
import com.fasterxml.jackson.databind.ObjectMapper; //
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Comment;
import io.sitprep.sitprepapi.dto.CommentDto; //
import io.sitprep.sitprepapi.dto.PostDto; //
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/**
 * A component responsible for sending real-time messages over WebSockets to connected clients.
 * This acts as a utility class for other services to broadcast updates.
 */
@Component
public class WebSocketMessageSender {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageSender.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper; //

    @Autowired
    public WebSocketMessageSender(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) { //
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper; //
    }

    /**
     * Sends a new Post event to clients subscribed to a specific group's post topic.
     * The image is base64 encoded for direct embedding if present.
     * @param groupId The ID of the group where the post was created.
     * @param post The new or updated Post object.
     */
    public void sendNewPost(String groupId, PostDto post) { // ✅ Updated to accept PostDto
        String destination = "/topic/posts/" + groupId;

        try {
            String jsonPayload = objectMapper.writeValueAsString(post);
            logger.info("🚀 WebSocket Broadcast: Attempting to send to {} with payload: {}", destination, jsonPayload);
        } catch (JsonProcessingException e) {
            logger.error("❌ WebSocket Broadcast: Failed to serialize PostDto for group {}", groupId, e);
        }

        messagingTemplate.convertAndSend(destination, post);
    }

    /**
     * Sends a new Comment event to clients subscribed to a specific post's comment topic.
     * @param postId The ID of the post to which the comment belongs.
     * @param comment The new or updated Comment object.
     */
    public void sendNewComment(Long postId, CommentDto comment) { // ✅ Updated to accept CommentDto
        String destination = "/topic/comments/" + postId;

        try {
            String jsonPayload = objectMapper.writeValueAsString(comment);
            logger.info("🚀 WebSocket Broadcast: Attempting to send to {} with payload: {}", destination, jsonPayload);
        } catch (JsonProcessingException e) {
            logger.error("❌ WebSocket Broadcast: Failed to serialize CommentDto for post {}", postId, e);
        }

        messagingTemplate.convertAndSend(destination, comment);
    }

    /**
     * Sends a generic update message to a specific topic.
     * This can be used for any other real-time updates (e.g., group status changes).
     * @param topic The WebSocket topic (e.g., "/topic/group-status/{groupId}").
     * @param payload The object to send (will be converted to JSON).
     */
    public void sendGenericUpdate(String topic, Object payload) {
        logger.info("Sending generic update to WebSocket topic: {}", topic);
        messagingTemplate.convertAndSend(topic, payload);
    }
}