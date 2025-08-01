// src/main/java/io/sitprep/sitprepapi/controller/TestWebSocketController.java
package io.sitprep.sitprepapi.controller;

import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/test")
public class TestWebSocketController {

    private final WebSocketMessageSender webSocketMessageSender;

    @Autowired
    public TestWebSocketController(WebSocketMessageSender sender) {
        this.webSocketMessageSender = sender;
    }

    @PostMapping("/ws-broadcast")
    public String broadcastTestPost(@RequestBody PostDto postDto) {
        if (postDto.getGroupId() == null) {
            return "❌ Missing groupId";
        }

        System.out.println("📬 Received test POST payload:");
        System.out.println(postDto);

        webSocketMessageSender.sendNewPost(String.valueOf(postDto.getGroupId()), postDto);
        return "✅ WebSocket broadcast sent to /topic/posts/" + postDto.getGroupId();
    }
}
