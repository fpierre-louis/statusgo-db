package io.sitprep.sitprepapi.controller;

import io.sitprep.sitprepapi.dto.GroupPostDto;
import io.sitprep.sitprepapi.service.GroupPostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.time.Instant;

@Controller
public class GroupPostWebSocketController {

    @Autowired
    private GroupPostService postService;

    @MessageMapping("/group-post")
    public void handleNewPost(GroupPostDto postDto,
                              @Header(name = "email", required = false) String emailHeader) {
        try {
            final String author = firstNonBlank(postDto.getAuthor(), emailHeader, "anonymous@sitprep");
            postDto.setAuthor(author);

            if (postDto.getTimestamp() == null) {
                postDto.setTimestamp(Instant.now());
            }

            postService.createPostFromDto(postDto, author);
        } catch (Exception e) {
            System.err.println("❌ Error saving post (MVP no-JWT): " + e.getMessage());
            e.printStackTrace();
        }
    }

    @MessageMapping("/group-post/delete")
    public void handleDeletePost(GroupPostDto postDto,
                                 @Header(name = "email", required = false) String emailHeader) {
        try {
            final String actor = firstNonBlank(emailHeader, "anonymous@sitprep");
            postService.deletePostAndBroadcast(postDto.getId(), actor);
            // GroupPostService should call wsSender.sendGroupPostDeletion(groupId, postId)
        } catch (Exception e) {
            System.err.println("❌ Error deleting post (MVP no-JWT): " + e.getMessage());
            e.printStackTrace();
        }
    }

    @MessageMapping("/group-post/edit")
    public void handleEditPost(GroupPostDto postDto,
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
