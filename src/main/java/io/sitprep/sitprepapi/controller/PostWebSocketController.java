package io.sitprep.sitprepapi.controller;

import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.service.PostService;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.time.Instant;

@Controller
public class PostWebSocketController {

    @Autowired
    private PostService postService;

    @Autowired
    private WebSocketMessageSender wsSender;

    @MessageMapping("/post")
    public void handleNewPost(PostDto postDto,
                              @Header(name = "email", required = false) String emailHeader) {
        try {
            final String author = firstNonBlank(postDto.getAuthor(), emailHeader, "anonymous@sitprep");
            postDto.setAuthor(author);

            if (postDto.getTimestamp() == null) {
                postDto.setTimestamp(Instant.now());
            }

            // Persist and get real id
            PostDto saved = postService.createPostFromDto(postDto, author);

            // Preserve tempId for optimistic replacement
            saved.setTempId(postDto.getTempId());

            if (saved.getGroupId() != null) {
                wsSender.sendNewPost(saved.getGroupId(), saved);
            } else {
                wsSender.sendGenericUpdate("posts", saved);
            }
        } catch (Exception e) {
            System.err.println("❌ Error saving post (MVP no-JWT): " + e.getMessage());
            e.printStackTrace();
        }
    }

    @MessageMapping("/post/delete")
    public void handleDeletePost(PostDto postDto,
                                 @Header(name = "email", required = false) String emailHeader) {
        try {
            final String actor = firstNonBlank(emailHeader, "anonymous@sitprep");
            postService.deletePostAndBroadcast(postDto.getId(), actor);
            // PostService should call wsSender.sendPostDeletion(groupId, postId)
        } catch (Exception e) {
            System.err.println("❌ Error deleting post (MVP no-JWT): " + e.getMessage());
            e.printStackTrace();
        }
    }

    @MessageMapping("/post/edit")
    public void handleEditPost(PostDto postDto,
                               @Header(name = "email", required = false) String emailHeader) {
        try {
            final String actor = firstNonBlank(postDto.getAuthor(), emailHeader, "anonymous@sitprep");
            postDto.setAuthor(actor);
            postService.updatePostFromDto(postDto);
            // Your service already broadcasts on update
        } catch (Exception e) {
            System.err.println("❌ Error editing post (MVP no-JWT): " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }
}
