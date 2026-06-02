package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.HouseholdEventDto;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.HouseholdEventService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    private final HouseholdAccessService access;

    public HouseholdEventResource(
            HouseholdEventService service,
            HouseholdAccessService access
    ) {
        this.service = service;
        this.access = access;
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

    /**
     * §6 of docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md — member micro-action
     * surface. Caller records their own confirmation of one of:
     *   • meeting (member-confirmed-meeting)
     *   • contacts (member-confirmed-contacts)
     *   • evac (member-confirmed-evac)
     *
     * <p>The {@code kind} path param accepts the short suffix
     * ({@code meeting} / {@code contacts} / {@code evac}) for a cleaner
     * URL; the service expands it to the full event-kind constant.
     * Any household member (admin OR non-admin) can record — the doc's
     * whole point is to give the non-admin side a daily action with
     * social meaning.</p>
     */
    @PostMapping("/member-confirmed/{shortKind}")
    public ResponseEntity<HouseholdEventDto> recordMemberConfirmation(
            @PathVariable String householdId,
            @PathVariable String shortKind
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        String fullKind = switch (shortKind == null ? "" : shortKind) {
            case "meeting"  -> HouseholdEventService.KIND_MEMBER_CONFIRMED_MEETING;
            case "contacts" -> HouseholdEventService.KIND_MEMBER_CONFIRMED_CONTACTS;
            case "evac"     -> HouseholdEventService.KIND_MEMBER_CONFIRMED_EVAC;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown confirmation kind: " + shortKind
            );
        };
        try {
            HouseholdEventDto dto = service.recordMemberConfirmation(
                    householdId, fullKind, caller
            );
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
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
