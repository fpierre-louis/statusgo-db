package io.sitprep.sitprepapi.controller;

import io.sitprep.sitprepapi.dto.CommentDto;
import io.sitprep.sitprepapi.service.CommentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.Instant;

@Controller
public class CommentWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(CommentWebSocketController.class);

    private final CommentService commentService;
    private final SimpMessagingTemplate messagingTemplate; // optional ACKs

    public CommentWebSocketController(CommentService commentService,
                                      SimpMessagingTemplate messagingTemplate) {
        this.commentService = commentService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Create a new comment.
     * Client sends: { postId, content, tempId, (optional) timestamp, (optional) author }
     * We derive author from header/payload (NOT trusted in MVP), then service persists & broadcasts.
     */
    @MessageMapping("/comment")
    public void handleNewComment(
            CommentDto dto,
            @Header(name = "email", required = false) String emailHeader
    ) {
        try {
            final String author = firstNonBlank(dto.getAuthor(), emailHeader, "anonymous@sitprep");
            dto.setAuthor(author);

            if (dto.getTimestamp() == null) {
                dto.setTimestamp(Instant.now());
            }

            // Persist and broadcast exactly once (service handles /topic/comments/{postId})
            commentService.createCommentFromDto(dto);

            // Optional: per-user ACK (skip in MVP)
            // if (emailHeader != null && dto.getTempId() != null) {
            //   messagingTemplate.convertAndSendToUser(emailHeader, "/queue/comments.ack",
            //     new Ack(dto.getTempId(), "received"));
            // }

        } catch (Exception e) {
            log.error("Error creating comment (MVP no-JWT): {}", e.getMessage(), e);
        }
    }

    /**
     * Edit existing comment.
     * Client sends: { id, postId, content, tempId? }
     * Service updates & broadcasts.
     */
    @MessageMapping("/comment/edit")
    public void handleEditComment(
            CommentDto dto,
            @Header(name = "email", required = false) String emailHeader
    ) {
        try {
            final String actor = firstNonBlank(dto.getAuthor(), emailHeader, "anonymous@sitprep");
            dto.setAuthor(actor);
            commentService.updateCommentFromDto(dto);
        } catch (Exception e) {
            log.error("Error editing comment (MVP no-JWT): {}", e.getMessage(), e);
        }
    }

    /**
     * Delete comment.
     * Client sends: { id, postId }
     * Service deletes & broadcasts /topic/comments/{postId}/delete.
     */
    @MessageMapping("/comment/delete")
    public void handleDeleteComment(DeletePayload payload) {
        if (payload == null || payload.getId() == null || payload.getPostId() == null) {
            log.warn("Delete comment payload missing id/postId: {}", payload);
            return;
        }
        try {
            commentService.deleteCommentAndBroadcast(payload.getId(), payload.getPostId());
        } catch (Exception e) {
            log.error("Error deleting comment (MVP no-JWT): {}", e.getMessage(), e);
        }
    }

    // ---- helpers ----

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
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
