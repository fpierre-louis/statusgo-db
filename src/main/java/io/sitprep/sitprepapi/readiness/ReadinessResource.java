package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ReadinessJourneyDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.SetItemStateRequest;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Ready for More" readiness journey (CONTRACT.md §6). Token-gated by the
 * {@code /api/**} chain; household membership is checked in the service.
 *
 * <ul>
 *   <li>{@code GET  /api/households/{id}/readiness} — member</li>
 *   <li>{@code PUT  /api/households/{id}/readiness/items/{itemKey}/state} — DONE / SKIPPED /
 *       REMIND_LATER for members, NOT_RELEVANT for admins</li>
 *   <li>{@code DELETE /api/households/{id}/readiness/items/{itemKey}/state/{state}} — idempotent</li>
 * </ul>
 * Every response is the full rebuilt journey.
 */
@RestController
@RequestMapping("/api/households/{householdId}/readiness")
public class ReadinessResource {

    private final ReadinessJourneyService journeyService;

    public ReadinessResource(ReadinessJourneyService journeyService) {
        this.journeyService = journeyService;
    }

    @GetMapping
    public ResponseEntity<ReadinessJourneyDto> getJourney(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(journeyService.getJourney(householdId, caller));
    }

    @PutMapping("/items/{itemKey}/state")
    public ResponseEntity<ReadinessJourneyDto> setState(@PathVariable String householdId,
                                                        @PathVariable String itemKey,
                                                        @RequestBody(required = false) SetItemStateRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(journeyService.setState(householdId, itemKey, body, caller));
    }

    @DeleteMapping("/items/{itemKey}/state/{state}")
    public ResponseEntity<ReadinessJourneyDto> clearState(@PathVariable String householdId,
                                                          @PathVariable String itemKey,
                                                          @PathVariable String state) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(journeyService.clearState(householdId, itemKey, state, caller));
    }
}
