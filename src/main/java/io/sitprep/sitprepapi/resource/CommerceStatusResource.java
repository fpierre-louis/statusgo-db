package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.CommerceSuppressionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cheap read of a household's commerce-suppression posture (2026-07-12). Any
 * affiliate surface that ISN'T already loading a payload with the flag baked in
 * — notably the Food Planner shopping list, whose list is computed client-side —
 * calls this once when its buy UI mounts, and hides retailer buttons when
 * {@code suppressed}. The 14-Day Kit / Supply Drawer read the same flag inline
 * off {@code HomeStockpileDto.commerceSuppressed}. Fail-open on the client (a
 * fetch error → show buttons); the suppression is an integrity nicety, not a
 * safety gate.
 */
@RestController
public class CommerceStatusResource {

    private final CommerceSuppressionService suppression;

    public CommerceStatusResource(CommerceSuppressionService suppression) {
        this.suppression = suppression;
    }

    public record CommerceStatusDto(boolean suppressed, String reason) {}

    @GetMapping("/api/households/{householdId}/commerce-status")
    public ResponseEntity<CommerceStatusDto> status(@PathVariable String householdId) {
        String reason = suppression.suppressionReason(householdId);
        return ResponseEntity.ok(new CommerceStatusDto(reason != null, reason));
    }
}
