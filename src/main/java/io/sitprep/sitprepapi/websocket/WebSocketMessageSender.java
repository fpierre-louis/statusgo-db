// src/main/java/io/sitprep/sitprepapi/websocket/WebSocketMessageSender.java
package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.dto.PostDto; // Import DTO
import io.sitprep.sitprepapi.dto.CommentDto; // Import DTO
import io.sitprep.sitprepapi.domain.Post; // Import Post if you want to send full Post objects
import io.sitprep.sitprepapi.domain.Comment; // Import Comment if you want to send full Comment objects
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64; // For image encoding

/**
 * A component responsible for sending real-time messages over WebSockets to connected clients.
 * This acts as a utility class for other services to broadcast updates.
 */
@Component
public class WebSocketMessageSender {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageSender.class);

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public WebSocketMessageSender(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Sends a new Post event to clients subscribed to a specific group's post topic.
     * The image is base64 encoded for direct embedding if present.
     * @param groupId The ID of the group where the post was created.
     * @param post The new or updated Post object.
     */
    public void sendNewPost(String groupId, Post post) {
        // Ensure transient fields like base64Image are set before sending over WebSocket
        if (post.getImage() != null && post.getBase64Image() == null) {
            String base64Image = Base64.getEncoder().encodeToString(post.getImage());
            post.setBase64Image("data:image/jpeg;base64," + base64Image); // Assuming JPEG, adjust if other types
        }
        String destination = "/topic/posts/" + groupId;
        logger.info("Sending new post to WebSocket topic: {}", destination);
        messagingTemplate.convertAndSend(destination, post);
    }

    /**
     * Sends a new Comment event to clients subscribed to a specific post's comment topic.
     * @param postId The ID of the post to which the comment belongs.
     * @param comment The new or updated Comment object.
     */
    public void sendNewComment(Long postId, Comment comment) {
        String destination = "/topic/comments/" + postId;
        logger.info("Sending new comment to WebSocket topic: {}", destination);
        messagingTemplate.convertAndSend(destination, comment);
    }
    public void sendNewPost(String groupId, PostDto postDto) { // ✅ Change Post to PostDto
        String destination = "/topic/posts/" + groupId;
        logger.info("Sending new post DTO to WebSocket topic: {}", destination);
        messagingTemplate.convertAndSend(destination, postDto); // Send DTO
    }

    public void sendNewComment(Long postId, CommentDto commentDto) { // ✅ Change Comment to CommentDto
        String destination = "/topic/comments/" + postId;
        logger.info("Sending new comment DTO to WebSocket topic: {}", destination);
        messagingTemplate.convertAndSend(destination, commentDto); // Send DTO
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