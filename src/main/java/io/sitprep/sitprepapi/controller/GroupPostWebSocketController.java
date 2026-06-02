package io.sitprep.sitprepapi.controller;

import io.sitprep.sitprepapi.dto.GroupPostDto;
import io.sitprep.sitprepapi.dto.GroupTypingRequest;
import io.sitprep.sitprepapi.service.GroupPostService;
import io.sitprep.sitprepapi.service.GroupTypingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;

@Controller
public class GroupPostWebSocketController {

    @Autowired
    private GroupPostService postService;

    @Autowired
    private GroupTypingService typingService;

    @MessageMapping("/group-post")
    public void handleNewPost(GroupPostDto postDto,
                              Principal principal) {
        try {
            final String author = requirePrincipalEmail(principal);
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
                                 Principal principal) {
        try {
            final String actor = requirePrincipalEmail(principal);
            postService.deletePostAndBroadcast(postDto.getId(), actor);
            // GroupPostService should call wsSender.sendGroupPostDeletion(groupId, postId)
        } catch (Exception e) {
            System.err.println("❌ Error deleting post (MVP no-JWT): " + e.getMessage());
            e.printStackTrace();
        }
    }

    @MessageMapping("/group-post/edit")
    public void handleEditPost(GroupPostDto postDto,
                               Principal principal) {
        try {
            final String actor = requirePrincipalEmail(principal);
            postDto.setAuthor(actor);
            postService.updatePostFromDto(postDto);
            // Your service already broadcasts on update
        } catch (Exception e) {
            System.err.println("❌ Error editing post (MVP no-JWT): " + e.getMessage());
            e.printStackTrace();
        }
    }

    @MessageMapping("/group-post/typing")
    public void handleTyping(GroupTypingRequest request, Principal principal) {
        try {
            typingService.relay(request.groupId(), requirePrincipalEmail(principal), request.typing());
        } catch (Exception e) {
            System.err.println("❌ Error relaying typing frame: " + e.getMessage());
        }
    }

    private static String requirePrincipalEmail(Principal principal) {
        String email = principal == null ? null : principal.getName();
        if (email == null || email.isBlank()) {
            throw new SecurityException("Authenticated WebSocket user required");
        }
        return email.trim().toLowerCase();
    }
}
