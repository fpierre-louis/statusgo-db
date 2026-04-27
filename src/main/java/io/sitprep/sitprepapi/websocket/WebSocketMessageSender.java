package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.dto.ChatMessageDtos.ChatMessageDto;
import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.AckDto;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.dto.CommentDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Centralized STOMP broadcast helper.
 * Topics:
 *  - Posts:       /topic/posts/{groupId}
 *  - Post del:    /topic/posts/{groupId}/delete
 *  - Comments:    /topic/comments/{postId}
 *  - Cmt del:     /topic/comments/{postId}/delete
 *  - Activations: /topic/activations/{activationId}/acks
 *  - Chat:        /topic/chat/{groupId}
 *  - Chat del:    /topic/chat/{groupId}/delete
 */
@Component
public class WebSocketMessageSender {
    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public WebSocketMessageSender(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // --- Posts ---
    public void sendNewPost(String groupId, PostDto dto) {
        messagingTemplate.convertAndSend("/topic/posts/" + groupId, dto);
    }

    public void sendPostDeletion(String groupId, Long postId) {
        messagingTemplate.convertAndSend("/topic/posts/" + groupId + "/delete", postId);
    }

    // --- Comments ---
    public void sendNewComment(Long postId, CommentDto dto) {
        messagingTemplate.convertAndSend("/topic/comments/" + postId, dto);
    }

    public void sendCommentDeletion(Long postId, Long commentId) {
        messagingTemplate.convertAndSend("/topic/comments/" + postId + "/delete", commentId);
    }

    // --- Activations ---
    public void sendActivationAck(String activationId, AckDto dto) {
        messagingTemplate.convertAndSend("/topic/activations/" + activationId + "/acks", dto);
    }

    // --- Chat ---
    public void sendChatMessage(String groupId, ChatMessageDto dto) {
        messagingTemplate.convertAndSend("/topic/chat/" + groupId, dto);
    }

    public void sendChatMessageDeletion(String groupId, Long messageId) {
        messagingTemplate.convertAndSend("/topic/chat/" + groupId + "/delete", messageId);
    }

    // --- Notifications ---
    public void sendInAppNotification(NotificationPayload payload) {
        messagingTemplate.convertAndSend("/topic/notifications", payload);
    }

    // --- Generic updates ---
    public void sendGenericUpdate(String topic, Object dto) {
        messagingTemplate.convertAndSend("/topic/" + topic, dto);
    }
}