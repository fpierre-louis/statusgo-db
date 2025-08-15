package io.sitprep.sitprepapi.controller;

import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.service.PostService;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;

@Controller
public class PostWebSocketController {

    @Autowired
    private PostService postService;

    @Autowired
    private WebSocketMessageSender wsSender;

    @MessageMapping("/post")
    public void handleNewPost(PostDto postDto, Principal principal) {
        if (principal == null) {
            System.err.println("❌ WebSocket principal is NULL — check token or frontend WS setup");
            return;
        }

        try {
            postDto.setAuthor(principal.getName());
            if (postDto.getTimestamp() == null) {
                postDto.setTimestamp(Instant.now());
            }

            // 1. Call the service and get the SAVED post back (with its real ID)
            PostDto savedPostDto = postService.createPostFromDto(postDto, principal.getName());

            // 2. Add the original tempId to the DTO that you're about to broadcast
            savedPostDto.setTempId(postDto.getTempId());

            // 3. Broadcast the complete DTO (with real ID and tempId)
            if (savedPostDto.getGroupId() != null) {
                wsSender.sendNewPost(savedPostDto.getGroupId(), savedPostDto);
            } else {
                wsSender.sendGenericUpdate("posts", savedPostDto);
            }

        } catch (Exception e) {
            System.err.println("❌ Error saving post from WebSocket: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @MessageMapping("/post/delete")
    public void handleDeletePost(PostDto postDto, Principal principal) {
        if (principal == null) {
            System.err.println("❌ WebSocket principal is NULL on DELETE");
            return;
        }

        try {
            postService.deletePostAndBroadcast(postDto.getId(), principal.getName());
        } catch (Exception e) {
            System.err.println("❌ Error deleting post from WebSocket: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @MessageMapping("/post/edit")
    public void handleEditPost(PostDto postDto, Principal principal) {
        if (principal == null) {
            System.err.println("❌ WebSocket principal is NULL on EDIT");
            return;
        }

        try {
            postDto.setAuthor(principal.getName());
            postService.updatePostFromDto(postDto);

            // The updatePostFromDto method now handles its own broadcasting

        } catch (Exception e) {
            System.err.println("❌ Error editing post from WebSocket: " + e.getMessage());
            e.printStackTrace();
        }
    }
}