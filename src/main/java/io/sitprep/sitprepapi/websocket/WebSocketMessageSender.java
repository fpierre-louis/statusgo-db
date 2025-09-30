package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.dto.CommentDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

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

    // --- Notifications ---
    public void sendInAppNotification(NotificationPayload payload) {
        messagingTemplate.convertAndSend("/topic/notifications", payload);
    }

    // --- Generic updates ---
    public void sendGenericUpdate(String topic, Object dto) {
        messagingTemplate.convertAndSend("/topic/" + topic, dto);
    }
}
