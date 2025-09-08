package io.sitprep.sitprepapi.controller;

import io.sitprep.sitprepapi.dto.CommentDto;
import io.sitprep.sitprepapi.service.CommentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;

@Controller
public class CommentWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(CommentWebSocketController.class);

    private final CommentService commentService;
    private final SimpMessagingTemplate messagingTemplate; // only for optional ACKs

    public CommentWebSocketController(CommentService commentService,
                                      SimpMessagingTemplate messagingTemplate) {
        this.commentService = commentService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Create a new comment.
     * Client sends: { postId, content, tempId, (optional) timestamp }
     * We set author from the authenticated Principal and delegate to the service,
     * which persists and broadcasts ONCE to /topic/comments.{postId}.
     */
    @MessageMapping("/comment")
    public void handleNewComment(Principal principal, CommentDto dto) {
        final String userEmail = principal != null ? principal.getName() : null;

        // Author comes from the authenticated principal (never trust the client)
        dto.setAuthor(userEmail);

        // Ensure server-side timestamp if client didn't set one
        if (dto.getTimestamp() == null) {
            dto.setTimestamp(Instant.now());
        }

        // ❗ No direct convertAndSend to /topic/... here.
        // Persist and broadcast exactly once (service sends to /topic/comments.{postId})
        commentService.createCommentFromDto(dto);

        // --- Optional: per-user ACK (not broadcast) ---
        // If you want an immediate receipt before DB/save finishes (usually unnecessary now),
        // uncomment the next lines. This sends only to the requesting user, not the topic.
        //
        // if (userEmail != null && dto.getTempId() != null) {
        //     messagingTemplate.convertAndSendToUser(
        //             userEmail,
        //             "/queue/comments.ack",
        //             new Ack(dto.getTempId(), "received")
        //     );
        // }
    }

    /**
     * Edit an existing comment.
     * Client sends: { id, postId, content, tempId? }
     * Service updates and broadcasts ONE comment update to /topic/comments.{postId}.
     */
    @MessageMapping("/comment/edit")
    public void handleEditComment(Principal principal, CommentDto dto) {
        // Optionally, enforce author == principal here if you want server-side ownership checks
        // (recommended if not already enforced elsewhere).

        commentService.updateCommentFromDto(dto);
        // No direct topic send here; the service already broadcasts the updated DTO.
    }

    /**
     * Delete a comment.
     * Client sends: { id, postId }
     * Service deletes and broadcasts deletion to /topic/comments.{postId}.delete.
     */
    @MessageMapping("/comment/delete")
    public void handleDeleteComment(DeletePayload payload) {
        if (payload == null || payload.getId() == null || payload.getPostId() == null) {
            log.warn("Delete comment payload missing id/postId: {}", payload);
            return;
        }
        commentService.deleteCommentAndBroadcast(payload.getId(), payload.getPostId());
    }

    /** Simple delete payload for STOMP messages. */
    public static class DeletePayload {
        private Long id;
        private Long postId;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getPostId() { return postId; }
        public void setPostId(Long postId) { this.postId = postId; }

        @Override
        public String toString() {
            return "DeletePayload{id=" + id + ", postId=" + postId + '}';
        }
    }

}
