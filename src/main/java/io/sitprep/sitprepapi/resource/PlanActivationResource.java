package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.PlanActivationDtos.*;
import io.sitprep.sitprepapi.service.PlanActivationService;
import io.sitprep.sitprepapi.service.PlanActivationService.ActivationExpiredException;
import io.sitprep.sitprepapi.util.AuthUtils;
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

    public PlanActivationResource(PlanActivationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ActivationCreatedDto> create(@RequestBody CreateActivationRequest request) {
        // Prefer the verified token's email when the Firebase filter populated
        // SecurityContext; fall back to the body param during rollout.
        String resolvedOwner = AuthUtils.resolveActor(
                request == null ? null : request.ownerEmail());
        CreateActivationRequest effective = request == null ? null : new CreateActivationRequest(
                resolvedOwner,
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
            @RequestBody AckRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.recordAck(activationId, request));
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
