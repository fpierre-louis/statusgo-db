package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.PlanActivationDtos.*;
import io.sitprep.sitprepapi.service.AckRateLimiter;
import io.sitprep.sitprepapi.service.PlanActivationService;
import io.sitprep.sitprepapi.service.PlanActivationService.ActivationExpiredException;
import io.sitprep.sitprepapi.util.AuthUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Routes for the "activate plan" flow.
 *
 * <pre>
 *   POST /api/plans/activations                  — owner fires on ACTIVATE
 *   GET  /api/plans/activations/{id}             — recipient-view snapshot + acks
 *   POST /api/plans/activations/{id}/acks        — recipient fires on tap
 *   GET  /api/plans/activations/{id}/acks        — owner poll (WS topic preferred)
 * </pre>
 *
 * WebSocket: {@code /topic/activations/{id}/acks} receives a new {@code AckDto}
 * after every successful ack commit.
 */
@RestController
@RequestMapping("/api/plans/activations")
public class PlanActivationResource {

    private final PlanActivationService service;
    private final AckRateLimiter ackRateLimiter;

    public PlanActivationResource(PlanActivationService service,
                                  AckRateLimiter ackRateLimiter) {
        this.service = service;
        this.ackRateLimiter = ackRateLimiter;
    }

    @PostMapping
    public ResponseEntity<ActivationCreatedDto> create(@RequestBody CreateActivationRequest request) {
        // Phase E enforcement — owner is whoever signed the request. Body's
        // ownerEmail is ignored; passing it (or a different email) would
        // otherwise let an attacker create activations on someone else's
        // behalf and share the link as if it were theirs.
        String owner = AuthUtils.requireAuthenticatedEmail();
        CreateActivationRequest effective = request == null ? null : new CreateActivationRequest(
                owner,
                request.meetingPlaceId(),
                request.evacPlanId(),
                request.meetingMode(),
                request.evacMode(),
                request.messagePreview(),
                request.location(),
                request.recipients()
        );
        ActivationCreatedDto created = service.createActivation(effective);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{activationId}")
    public ResponseEntity<ActivationDetailDto> get(@PathVariable String activationId) {
        return service.getActivation(activationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{activationId}/acks")
    public ResponseEntity<AckDto> ack(
            @PathVariable String activationId,
            @RequestBody AckRequest request,
            HttpServletRequest http
    ) {
        // Defensive rate limit — this endpoint is intentionally un-authed
        // (recipients may not have a SitPrep account). Cap anonymous
        // (activationId, IP) pairs at 10/minute per LAUNCH_READINESS.md
        // privacy/safety section. Returns 429 when exceeded so the FE can
        // surface a "slow down" state.
        String ip = clientIp(http);
        if (!ackRateLimiter.tryConsume(activationId, ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.recordAck(activationId, request));
    }

    /**
     * Resolve the request's client IP. On Heroku (and any reverse-proxied
     * setup) the {@code X-Forwarded-For} header carries the original
     * client; the socket peer is the proxy. Falls back to the socket peer
     * when the header is absent (local dev). Takes the first hop only —
     * later hops in the chain are downstream proxies under our control.
     */
    private static String clientIp(HttpServletRequest http) {
        if (http == null) return null;
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return http.getRemoteAddr();
    }

    @GetMapping("/{activationId}/acks")
    public ResponseEntity<List<AckDto>> listAcks(@PathVariable String activationId) {
        return ResponseEntity.ok(service.getAcks(activationId));
    }

    // -----------------------------
    // Exception mapping
    // -----------------------------

    @ExceptionHandler(ActivationExpiredException.class)
    public ResponseEntity<String> gone(ActivationExpiredException ex) {
        return ResponseEntity.status(HttpStatus.GONE).body(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
