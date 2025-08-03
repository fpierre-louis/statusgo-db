package io.sitprep.sitprepapi.controller;

import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.service.PostService;
import io.sitprep.sitprepapi.domain.Post;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class PostWebSocketController {

    @Autowired
    private PostService postService;

    @MessageMapping("/post")
    public void handleNewPost(PostDto postDto, Principal principal) {
        if (principal == null) {
            System.err.println("❌ WebSocket principal is NULL — check token or frontend WS setup");
            return;
        }

        try {
            postDto.setAuthor(principal.getName());
            postService.createPostFromDto(postDto, principal.getName());


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
            postDto.setAuthor(principal.getName()); // ✅ Defensive overwrite
            postService.updatePostFromDto(postDto);
        } catch (Exception e) {
            System.err.println("❌ Error editing post from WebSocket: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
