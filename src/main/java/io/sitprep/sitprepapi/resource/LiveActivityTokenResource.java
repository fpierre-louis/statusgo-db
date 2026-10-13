package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.LiveActivityTokenService;
import io.sitprep.sitprepapi.service.LiveActivityTokenService.RegisterRequest;
import io.sitprep.sitprepapi.service.LiveActivityTokenService.TokenDto;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live Activity push-token registration, called by the native
 * {@code LiveActivityTokenReporter} (live_activities GAMEPLAN §2.5, §3.5).
 * Contract: {@code docs/epics/live_activities/EXEC-P1-backend.md}.
 *
 * <ul>
 *   <li>{@code PUT /api/live-activities/tokens} — register or rotate a token (200 + the row, no token echoed).</li>
 *   <li>{@code DELETE /api/live-activities/tokens/{activityId}} — stop following an activity (204, idempotent).</li>
 * </ul>
 *
 * <p>Both require a verified Firebase token; the owner is the caller, never the body.</p>
 */
@RestController
@RequestMapping("/api/live-activities/tokens")
public class LiveActivityTokenResource {

    private final LiveActivityTokenService service;

    public LiveActivityTokenResource(LiveActivityTokenService service) {
        this.service = service;
    }

    @PutMapping
    public ResponseEntity<TokenDto> register(@RequestBody RegisterRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.register(caller, body));
    }

    @DeleteMapping("/{activityId}")
    public ResponseEntity<Void> unregister(@PathVariable String activityId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        service.unregister(caller, activityId);
        return ResponseEntity.noContent().build();
    }
}
