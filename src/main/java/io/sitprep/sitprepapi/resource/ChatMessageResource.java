package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ChatMessageDtos.*;
import io.sitprep.sitprepapi.service.ChatMessageService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Chat messages, scoped to a group (today: household). Routes:
 *
 * <pre>
 *   POST   /api/chat/{groupId}/messages
 *   GET    /api/chat/{groupId}/messages?before=isoTs&limit=50
 *   GET    /api/chat/{groupId}/messages/since?sinceIso=isoTs
 *   PUT    /api/chat/messages/{id}
 *   DELETE /api/chat/messages/{id}?actor=email@...
 * </pre>
 *
 * WebSocket: {@code /topic/chat/{groupId}} receives a {@link ChatMessageDto}
 * after create/edit commits; {@code /topic/chat/{groupId}/delete} receives
 * the deleted message id.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatMessageResource {

    private final ChatMessageService service;

    public ChatMessageResource(ChatMessageService service) {
        this.service = service;
    }

    @PostMapping("/{groupId}/messages")
    public ResponseEntity<ChatMessageDto> create(
            @PathVariable String groupId,
            @RequestBody CreateMessageRequest request) {
        CreateMessageRequest effective = request == null ? null : new CreateMessageRequest(
                AuthUtils.requireAuthenticatedEmail(),
                request.content(),
                request.tempId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(groupId, effective));
    }

    @GetMapping("/{groupId}/messages")
    public ResponseEntity<MessagesPage> page(
            @PathVariable String groupId,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) Integer limit) {
        AuthUtils.requireAuthenticatedEmail();
        Instant beforeTs = parseInstant(before);
        return ResponseEntity.ok(service.getPage(groupId, beforeTs, limit));
    }

    @GetMapping("/{groupId}/messages/since")
    public ResponseEntity<List<ChatMessageDto>> since(
            @PathVariable String groupId,
            @RequestParam String sinceIso) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.getSince(groupId, parseInstant(sinceIso)));
    }

    @PutMapping("/messages/{id}")
    public ResponseEntity<ChatMessageDto> edit(
            @PathVariable Long id,
            @RequestBody UpdateMessageRequest request) {
        UpdateMessageRequest effective = request == null ? null : new UpdateMessageRequest(
                AuthUtils.requireAuthenticatedEmail(), // service still verifies authorship
                request.content()
        );
        return ResponseEntity.ok(service.edit(id, effective));
    }

    @DeleteMapping("/messages/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestParam(required = false) String actor) {
        // actor query param ignored — caller is the verified token email.
        // Kept on the signature to avoid 4xx on legacy clients still sending it.
        service.delete(id, AuthUtils.requireAuthenticatedEmail());
        return ResponseEntity.noContent().build();
    }

    // -----------------------------
    // Helpers / exception mapping
    // -----------------------------

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 timestamp: " + iso);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<String> forbidden(SecurityException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.getMessage());
    }
}
