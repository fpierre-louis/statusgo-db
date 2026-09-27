package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.MapConfirmationDtos.ConfirmRequest;
import io.sitprep.sitprepapi.dto.MapConfirmationDtos.ConfirmResponse;
import io.sitprep.sitprepapi.service.MapConfirmationService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * "Still here?" — {@code POST /api/map/confirmations} (V85, map-ideal BE-7).
 *
 * <ul>
 *   <li>200 {@code {count, lastAt, mine: true}} — recorded; the counts include the caller.</li>
 *   <li>429 {@code {count, lastAt, mine: true, retryAfterSeconds}} + {@code Retry-After} —
 *       the caller confirmed this target less than 10 minutes ago.</li>
 *   <li>400 unknown {@code targetType} / malformed {@code targetId}; 404 the
 *       target is not a place the map shows; 401 without a verified token.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/map")
public class MapConfirmationResource {

    private final MapConfirmationService service;

    public MapConfirmationResource(MapConfirmationService service) {
        this.service = service;
    }

    @PostMapping("/confirmations")
    public ResponseEntity<ConfirmResponse> confirm(@RequestBody ConfirmRequest req) {
        String email = AuthUtils.requireAuthenticatedEmail();
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body required");
        MapConfirmationService.Outcome o;
        try {
            o = service.confirm(req.targetType(), req.targetId(), email, Instant.now());
        } catch (DataIntegrityViolationException raced) {
            // Two first-confirms from the same person landed together; the
            // unique index kept one. The other is, by definition, inside the
            // cooldown.
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(MapConfirmationService.COOLDOWN.toSeconds()))
                    .build();
        }
        if (!o.accepted()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(o.retryAfterSeconds()))
                    .body(new ConfirmResponse(o.count(), o.lastAt(), true, o.retryAfterSeconds()));
        }
        return ResponseEntity.ok(new ConfirmResponse(o.count(), o.lastAt(), true, null));
    }
}
