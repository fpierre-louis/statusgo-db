package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.TogetherSessionService.SessionDto;
import io.sitprep.sitprepapi.util.AuthUtils;
import io.sitprep.sitprepapi.web.Idempotent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Together sessions (FR-8). Token-gated by the {@code /api/**} chain; household access is in the service. */
@RestController
public class TogetherSessionResource {

    private final TogetherSessionService service;

    public TogetherSessionResource(TogetherSessionService service) {
        this.service = service;
    }

    public record CreateRequest(String householdId, String activityKey, String style) {}

    public record TurnRequest(Integer turnIndex) {}

    @PostMapping("/api/practice/together/sessions")
    @Idempotent
    public ResponseEntity<SessionDto> create(@RequestBody(required = false) CreateRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.create(caller, body == null ? null : body.householdId(),
                body == null ? null : body.activityKey(), body == null ? null : body.style()));
    }

    @PostMapping("/api/practice/together/sessions/{id}/turn")
    @Idempotent
    public ResponseEntity<SessionDto> turn(@PathVariable("id") long id, @RequestBody(required = false) TurnRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.turn(caller, id, body == null ? null : body.turnIndex()));
    }
}
