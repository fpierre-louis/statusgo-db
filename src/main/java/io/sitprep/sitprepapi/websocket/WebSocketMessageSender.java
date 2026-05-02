package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.AckDto;
import io.sitprep.sitprepapi.dto.HouseholdAccompanimentDto;
import io.sitprep.sitprepapi.dto.HouseholdEventDto;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.dto.GroupPostDto;
import io.sitprep.sitprepapi.dto.GroupPostReactionFrame;
import io.sitprep.sitprepapi.dto.GroupPostCommentDto;
import io.sitprep.sitprepapi.dto.TaskCommentDto;
import io.sitprep.sitprepapi.dto.TaskDto;
import io.sitprep.sitprepapi.dto.TaskReactionFrame;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Centralized STOMP broadcast helper.
 * Topics:
 *  - Posts:       /topic/posts/{groupId}
 *  - GroupPost del:    /topic/posts/{groupId}/delete
 *  - Comments:    /topic/comments/{postId}
 *  - Cmt del:     /topic/comments/{postId}/delete
 *  - Task cmts:   /topic/task-comments/{taskId}
 *  - Task cmt del:/topic/task-comments/{taskId}/delete
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
    public void sendNewPost(String groupId, GroupPostDto dto) {
        messagingTemplate.convertAndSend("/topic/posts/" + groupId, dto);
    }

    public void sendPostDeletion(String groupId, Long postId) {
        messagingTemplate.convertAndSend("/topic/posts/" + groupId + "/delete", postId);
    }

    /**
     * Broadcast an emoji reaction add/remove on the same posts topic. The
     * frame's {@code type:"reaction"} discriminator lets the post-list
     * subscriber ignore it (it's not a full GroupPostDto) while the reactions
     * subscriber picks it up.
     */
    public void sendPostReaction(String groupId, GroupPostReactionFrame frame) {
        if (groupId == null || groupId.isBlank() || frame == null) return;
        messagingTemplate.convertAndSend("/topic/posts/" + groupId, frame);
    }

    // --- Comments ---
    public void sendNewComment(Long postId, GroupPostCommentDto dto) {
        messagingTemplate.convertAndSend("/topic/comments/" + postId, dto);
    }

    public void sendCommentDeletion(Long postId, Long commentId) {
        messagingTemplate.convertAndSend("/topic/comments/" + postId + "/delete", commentId);
    }

    // --- Task comments ---
    /**
     * New / edited comment on a community-feed task. Topic is parallel to
     * the post-comments topic ({@code /topic/comments/{postId}}) but
     * scoped under {@code /topic/task-comments/} so the FE can subscribe
     * cleanly without sniffing payload shape.
     *
     * <p>Same convention as {@link #sendNewComment(Long, GroupPostCommentDto)}:
     * create + edit deltas ride the same topic; the FE upserts by id.</p>
     */
    public void sendNewTaskComment(Long taskId, TaskCommentDto dto) {
        if (taskId == null || dto == null) return;
        messagingTemplate.convertAndSend("/topic/task-comments/" + taskId, dto);
    }

    public void sendTaskCommentDeletion(Long taskId, Long commentId) {
        if (taskId == null || commentId == null) return;
        messagingTemplate.convertAndSend("/topic/task-comments/" + taskId + "/delete", commentId);
    }

    // --- Activations ---
    public void sendActivationAck(String activationId, AckDto dto) {
        messagingTemplate.convertAndSend("/topic/activations/" + activationId + "/acks", dto);
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

    /**
     * Broadcast an emoji reaction add/remove on the same task topic the
     * task itself rides on. The frame's {@code type:"reaction"}
     * discriminator lets the task-list subscriber ignore it (it's not
     * a full TaskDto) while a reactions subscriber picks it up.
     *
     * <p>Routes to whichever topic the task lives on — group-scope
     * tasks go to {@code /topic/group/{groupId}/tasks}, community-scope
     * tasks go to {@code /topic/community/tasks/{zipBucket}}. Both
     * endpoints can carry reaction frames on the same channel because
     * the FE listens with a discriminator switch.</p>
     */
    public void sendTaskReaction(TaskReactionFrame frame) {
        if (frame == null) return;
        if (frame.groupId() != null && !frame.groupId().isBlank()) {
            messagingTemplate.convertAndSend(
                    "/topic/group/" + frame.groupId() + "/tasks", frame);
        } else if (frame.zipBucket() != null && !frame.zipBucket().isBlank()) {
            messagingTemplate.convertAndSend(
                    "/topic/community/tasks/" + frame.zipBucket(), frame);
        }
    }

    // --- Notifications ---
    public void sendInAppNotification(NotificationPayload payload) {
        messagingTemplate.convertAndSend("/topic/notifications", payload);
    }

    /**
     * Per-user inbox topic — drives the live-update path on the
     * notifications inbox page (per docs/NOTIFICATIONS_INBOX.md). Lane B
     * silent rows + Lane A audit rows + read-state changes (mark-read,
     * mark-all-read, archive) all flow through here so the FE can patch
     * its local list optimistically AND reconcile from the server.
     *
     * <p>Topic format: {@code /topic/notifications/{lowercased-email}}.
     * Lowercase normalization is the same convention used elsewhere
     * (Follow / Block) so a forged-case email can't subscribe to a
     * different inbox. Null/blank user is a silent no-op.</p>
     *
     * <p>Payload shape — kind-tagged so the FE can switch:</p>
     * <pre>
     *   { "kind": "created",   "row": NotificationLogDto }
     *   { "kind": "read",      "id": Long, "readAt": Instant }
     *   { "kind": "all-read",  "before": Instant, "readAt": Instant }
     *   { "kind": "archived",  "id": Long }
     * </pre>
     */
    public void sendInboxEvent(String userEmail, Object payload) {
        if (userEmail == null || userEmail.isBlank()) return;
        String key = userEmail.trim().toLowerCase();
        messagingTemplate.convertAndSend("/topic/notifications/" + key, payload);
    }

    // --- Generic updates ---
    public void sendGenericUpdate(String topic, Object dto) {
        messagingTemplate.convertAndSend("/topic/" + topic, dto);
    }
}