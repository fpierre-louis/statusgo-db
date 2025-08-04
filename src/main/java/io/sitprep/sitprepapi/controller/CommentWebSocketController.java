package io.sitprep.sitprepapi.controller;

import io.sitprep.sitprepapi.dto.CommentDto;
import io.sitprep.sitprepapi.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class CommentWebSocketController {

    @Autowired
    private CommentService commentService;

    @MessageMapping("/comment")
    public void handleNewComment(CommentDto commentDto, Principal principal) {
        if (principal == null) {
            System.err.println("❌ WebSocket principal is NULL on COMMENT CREATE");
            return;
        }

        try {
            commentDto.setAuthor(principal.getName());
            commentService.createCommentFromDto(commentDto);
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
            commentService.updateCommentFromDto(commentDto);
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
            commentService.deleteCommentAndBroadcast(commentDto.getId(), commentDto.getPostId());
        } catch (Exception e) {
            System.err.println("❌ Error deleting comment via WebSocket: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
