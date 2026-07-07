package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.DmDtos.DmMessageDto;
import io.sitprep.sitprepapi.dto.DmDtos.DmThreadDto;
import io.sitprep.sitprepapi.dto.DmDtos.SendMessageRequest;
import io.sitprep.sitprepapi.service.DmService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Direct messages.
 *
 * <pre>
 *   GET   /api/dm/threads                      → viewer's inbox
 *   GET   /api/dm/threads/{threadId}/messages  → full thread, oldest → newest
 *   POST  /api/dm/messages { peerEmail, body } → send (creates thread on first message)
 *   PATCH /api/dm/threads/{threadId}/read      → move viewer's read watermark
 * </pre>
 *
 * <p>Send targets ride the request body (not the path) so identity
 * emails never fight URL encoding. Live updates: STOMP
 * {@code /topic/dm/{viewerEmail}}.</p>
 */
@RestController
@RequestMapping("/api/dm")
public class DmResource {

    private final DmService service;

    public DmResource(DmService service) {
        this.service = service;
    }

    @GetMapping("/threads")
    public ResponseEntity<List<DmThreadDto>> inbox() {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.inbox(viewer));
    }

    @GetMapping("/threads/{threadId}/messages")
    public ResponseEntity<List<DmMessageDto>> messages(@PathVariable Long threadId) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.messages(threadId, viewer));
    }

    @PostMapping("/messages")
    public ResponseEntity<DmMessageDto> send(@RequestBody SendMessageRequest req) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        DmMessageDto sent = service.send(viewer, req.peerEmail(), req.body());
        return ResponseEntity.status(HttpStatus.CREATED).body(sent);
    }

    @PatchMapping("/threads/{threadId}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long threadId) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        service.markRead(threadId, viewer);
        return ResponseEntity.noContent().build();
    }
}
