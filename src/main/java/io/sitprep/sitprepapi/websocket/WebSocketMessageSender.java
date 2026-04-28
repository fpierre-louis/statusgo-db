package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.dto.ChatMessageDtos.ChatMessageDto;
import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.AckDto;
import io.sitprep.sitprepapi.dto.HouseholdAccompanimentDto;
import io.sitprep.sitprepapi.dto.HouseholdEventDto;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.dto.PostReactionFrame;
import io.sitprep.sitprepapi.dto.CommentDto;
import io.sitprep.sitprepapi.dto.TaskDto;

import java.util.List;
import java.util.Map;
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
 *  - Group tasks: /topic/group/{groupId}/tasks
 *  - Group task del: /topic/group/{groupId}/tasks/delete
 *  - Community tasks (by zip-bucket): /topic/community/tasks/{zipBucket}
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

    /**
     * Broadcast an emoji reaction add/remove on the same posts topic. The
     * frame's {@code type:"reaction"} discriminator lets the post-list
     * subscriber ignore it (it's not a full PostDto) while the reactions
     * subscriber picks it up.
     */
    public void sendPostReaction(String groupId, PostReactionFrame frame) {
        if (groupId == null || groupId.isBlank() || frame == null) return;
        messagingTemplate.convertAndSend("/topic/posts/" + groupId, frame);
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

    // --- Tasks ---
    /**
     * Broadcasts a task create/update/lifecycle change. Routing depends on
     * the task's scope:
     *  - groupId != null  -> /topic/group/{groupId}/tasks
     *  - groupId == null  -> /topic/community/tasks/{zipBucket} (when zip is known)
     * Tasks claimed by a group ALSO broadcast on the claimer group's topic
     * so admin dashboards see them appear under "tasks we own".
     */
    public void sendTaskUpdate(TaskDto dto) {
        if (dto == null) return;
        if (dto.groupId() != null && !dto.groupId().isBlank()) {
            messagingTemplate.convertAndSend("/topic/group/" + dto.groupId() + "/tasks", dto);
        } else if (dto.zipBucket() != null && !dto.zipBucket().isBlank()) {
            messagingTemplate.convertAndSend("/topic/community/tasks/" + dto.zipBucket(), dto);
        }
        if (dto.claimedByGroupId() != null && !dto.claimedByGroupId().isBlank()
                && !dto.claimedByGroupId().equals(dto.groupId())) {
            messagingTemplate.convertAndSend("/topic/group/" + dto.claimedByGroupId() + "/tasks", dto);
        }
    }

    // --- Household events ---
    /**
     * Broadcast a new household activity event so the chat thread updates
     * live without a refresh. Topic mirrors the REST resource path:
     *   /topic/households/{householdId}/events
     */
    public void sendHouseholdEvent(String householdId, HouseholdEventDto dto) {
        if (householdId == null || householdId.isBlank() || dto == null) return;
        messagingTemplate.convertAndSend(
                "/topic/households/" + householdId + "/events", dto);
    }

    // --- Household accompaniments (with-me feature) ---
    public void sendHouseholdAccompanimentUpdate(String householdId, HouseholdAccompanimentDto dto) {
        if (householdId == null || householdId.isBlank() || dto == null) return;
        messagingTemplate.convertAndSend(
                "/topic/households/" + householdId + "/accompaniments", dto);
    }

    public void sendHouseholdAccompanimentRelease(String householdId, Map<String, String> ref) {
        if (householdId == null || householdId.isBlank() || ref == null) return;
        messagingTemplate.convertAndSend(
                "/topic/households/" + householdId + "/accompaniments/release", ref);
    }

    /**
     * Replace-all snapshot push — used after a manual-member removal cascade
     * since the per-row delete events would be racy.
     */
    public void sendHouseholdAccompanimentReplaceAll(String householdId, List<HouseholdAccompanimentDto> all) {
        if (householdId == null || householdId.isBlank()) return;
        messagingTemplate.convertAndSend(
                "/topic/households/" + householdId + "/accompaniments/snapshot", all);
    }

    // --- Household manual members ---
    public void sendHouseholdManualMemberUpdate(String householdId, HouseholdManualMemberDto dto) {
        if (householdId == null || householdId.isBlank() || dto == null) return;
        messagingTemplate.convertAndSend(
                "/topic/households/" + householdId + "/manual-members", dto);
    }

    public void sendHouseholdManualMemberDeletion(String householdId, String manualMemberId) {
        if (householdId == null || householdId.isBlank() || manualMemberId == null) return;
        messagingTemplate.convertAndSend(
                "/topic/households/" + householdId + "/manual-members/delete", manualMemberId);
    }

    public void sendTaskDeletion(String groupId, String zipBucket, Long taskId) {
        if (groupId != null && !groupId.isBlank()) {
            messagingTemplate.convertAndSend("/topic/group/" + groupId + "/tasks/delete", taskId);
        } else if (zipBucket != null && !zipBucket.isBlank()) {
            messagingTemplate.convertAndSend("/topic/community/tasks/" + zipBucket + "/delete", taskId);
        }
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