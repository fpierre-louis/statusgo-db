package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.HouseholdInviteRequestDto;
import io.sitprep.sitprepapi.service.HouseholdInviteRequestService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Member-initiated invite request endpoints. See
 * {@link HouseholdInviteRequestService} for the flow + auth model.
 *
 * <ul>
 *   <li>{@code POST /api/households/{householdId}/invite-requests} —
 *       member files a request.</li>
 *   <li>{@code GET /api/households/{householdId}/invite-requests} —
 *       admin lists pending.</li>
 *   <li>{@code POST /api/households/{householdId}/invite-requests/{id}/approve} —
 *       admin approves (adds candidate to household).</li>
 *   <li>{@code POST /api/households/{householdId}/invite-requests/{id}/decline} —
 *       admin declines.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/households/{householdId}/invite-requests")
public class HouseholdInviteRequestResource {

    private final HouseholdInviteRequestService service;

    public HouseholdInviteRequestResource(HouseholdInviteRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<HouseholdInviteRequestDto> create(
            @PathVariable String householdId,
            @RequestBody CreateRequest body) {
        String requester = AuthUtils.requireAuthenticatedEmail();
        HouseholdInviteRequestDto out = service.create(householdId, requester, body == null ? null : body.candidateEmail());
        return ResponseEntity.ok(out);
    }

    @GetMapping
    public ResponseEntity<List<HouseholdInviteRequestDto>> listPending(@PathVariable String householdId) {
        String admin = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.listPending(householdId, admin));
    }

    @PostMapping("/{requestId}/approve")
    public ResponseEntity<HouseholdInviteRequestDto> approve(
            @PathVariable String householdId,
            @PathVariable String requestId) {
        String admin = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.approve(householdId, requestId, admin));
    }

    @PostMapping("/{requestId}/decline")
    public ResponseEntity<HouseholdInviteRequestDto> decline(
            @PathVariable String householdId,
            @PathVariable String requestId) {
        String admin = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.decline(householdId, requestId, admin));
    }

    public record CreateRequest(String candidateEmail) {}
}
