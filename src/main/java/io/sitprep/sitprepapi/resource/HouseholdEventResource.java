package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.HouseholdEventDto;
import io.sitprep.sitprepapi.service.HouseholdEventService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Household activity events — chronological log feeding the chat thread's
 * "system event" rows. Replaces the FE's client-side synthesis.
 *
 * <p>Both bounds are optional. Common usage patterns:
 * <ul>
 *   <li>{@code GET /api/households/{id}/events} — full log (capped by repo)</li>
 *   <li>{@code GET /api/households/{id}/events?since=...} — incremental
 *       fetch when reconnecting after a WS dropout</li>
 * </ul>
 *
 * <p>Live updates: STOMP topic {@code /topic/households/{id}/events}.</p>
 */
@RestController
@RequestMapping("/api/households/{householdId}/events")
public class HouseholdEventResource {

    private final HouseholdEventService service;

    public HouseholdEventResource(HouseholdEventService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<HouseholdEventDto>> list(
            @PathVariable String householdId,
            @RequestParam(value = "since", required = false) String sinceIso,
            @RequestParam(value = "before", required = false) String beforeIso) {
        AuthUtils.requireAuthenticatedEmail();
        Instant since = parse(sinceIso);
        Instant before = parse(beforeIso);
        return ResponseEntity.ok(service.list(householdId, since, before));
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
