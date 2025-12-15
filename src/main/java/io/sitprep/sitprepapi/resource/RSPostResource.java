package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.RSPostDto;
import io.sitprep.sitprepapi.service.RSPostService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rs/posts")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class RSPostResource {

    private final RSPostService service;

    @GetMapping("/group/{groupId}")
    public List<RSPostDto> byGroup(@PathVariable String groupId) {
        return service.getPostsByGroup(groupId);
    }

    // Minimal create: POST /api/rs/posts/group/{groupId}?email=... with JSON { "content": "..." }
    @PostMapping("/group/{groupId}")
    public ResponseEntity<RSPostDto> create(@PathVariable String groupId,
                                            @RequestParam(value = "email", required = false) String email,
                                            @RequestBody CreatePostBody body) {
        return ResponseEntity.ok(service.createPost(groupId, email, body.content()));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> delete(@PathVariable UUID postId,
                                       @RequestParam(value = "email", required = false) String email) {
        service.deletePost(postId, email);
        return ResponseEntity.noContent().build();
    }

    public record CreatePostBody(String content) {}
}