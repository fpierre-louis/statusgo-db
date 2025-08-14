package io.sitprep.sitprepapi.controller;

import io.sitprep.sitprepapi.dto.CommentDto;
import io.sitprep.sitprepapi.service.CommentService;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;

@Controller
public class CommentWebSocketController {

    @Autowired
    private CommentService commentService;

    @Autowired
    private WebSocketMessageSender wsSender;

    @MessageMapping("/comment")
    public void handleNewComment(CommentDto commentDto, Principal principal) {
        if (principal == null) {
            System.err.println("❌ WebSocket principal is NULL on COMMENT CREATE");
            return;
        }

        try {
            commentDto.setAuthor(principal.getName());
            if (commentDto.getTimestamp() == null) {
                commentDto.setTimestamp(Instant.now());
            }

            // Works whether this returns void or an entity
            commentService.createCommentFromDto(commentDto);

            // 🔊 Broadcast to post-specific comments topic
            if (commentDto.getPostId() != null) {
                wsSender.sendNewComment(commentDto.getPostId(), commentDto);
            }

        } catch (Exception e) {
            System.err.println("❌ Error creating comment via WebSocket: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @MessageMapping("/comment/edit")
    public void handleEditComment(CommentDto commentDto, Principal principal) {
        if (principal == null) {
            System.err.println("❌ WebSocket principal is NULL on COMMENT EDIT");
            return;
        }

        try {
            commentDto.setAuthor(principal.getName());

            // Works whether this returns void or an entity
            commentService.updateCommentFromDto(commentDto);

            // 🔊 Optional live-update for comment edits
            if (commentDto.getPostId() != null) {
                wsSender.sendGenericUpdate("/topic/comments/" + commentDto.getPostId() + "/edit", commentDto);
            }

        } catch (Exception e) {
            System.err.println("❌ Error editing comment via WebSocket: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @MessageMapping("/comment/delete")
    public void handleDeleteComment(CommentDto commentDto, Principal principal) {
        if (principal == null) {
            System.err.println("❌ WebSocket principal is NULL on COMMENT DELETE");
            return;
        }

        try {
            // Service already handles broadcast based on method name
            commentService.deleteCommentAndBroadcast(commentDto.getId(), commentDto.getPostId());
        } catch (Exception e) {
            System.err.println("❌ Error deleting comment via WebSocket: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
