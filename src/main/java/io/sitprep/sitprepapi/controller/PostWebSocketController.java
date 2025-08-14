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
            // Ensure author/timestamp are set
            postDto.setAuthor(principal.getName());
            if (postDto.getTimestamp() == null) {
                postDto.setTimestamp(Instant.now());
            }

            // Works whether this returns void or an entity
            postService.createPostFromDto(postDto, principal.getName());

            // 🔊 Broadcast using the DTO we have
            if (postDto.getGroupId() != null) {
                wsSender.sendNewPost(postDto.getGroupId(), postDto);
            } else {
                wsSender.sendGenericUpdate("/topic/posts", postDto);
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
            // Your service already broadcasts deletion (per your method name)
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
            postDto.setAuthor(principal.getName()); // defensive overwrite
            // Works whether this returns void or an entity
            postService.updatePostFromDto(postDto);

            // 🔊 Optional broadcast so other clients live-update
            if (postDto.getGroupId() != null) {
                wsSender.sendGenericUpdate("/topic/posts/" + postDto.getGroupId() + "/edit", postDto);
            } else {
                wsSender.sendGenericUpdate("/topic/posts/edit", postDto);
            }

        } catch (Exception e) {
            System.err.println("❌ Error editing post from WebSocket: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
