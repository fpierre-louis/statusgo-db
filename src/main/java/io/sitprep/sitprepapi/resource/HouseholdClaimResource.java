package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.HouseholdClaimService;
import io.sitprep.sitprepapi.service.HouseholdClaimService.AcceptResult;
import io.sitprep.sitprepapi.service.HouseholdClaimService.ClaimInvite;
import io.sitprep.sitprepapi.service.HouseholdClaimService.ClaimStateException;
import io.sitprep.sitprepapi.service.HouseholdClaimService.Preview;
import io.sitprep.sitprepapi.service.HouseholdClaimService.State;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * "Claim your spot" links (household roster EXEC-B). See {@link HouseholdClaimService}.
 *
 * <ul>
 *   <li>{@code POST   /api/households/{id}/manual-members/{mid}/claim-invite} —
 *       admin. 201 new link / 200 the live one reused. 429 past the daily cap.</li>
 *   <li>{@code DELETE /api/households/{id}/manual-members/{mid}/claim-invite} —
 *       admin. Revokes any open link. 204.</li>
 *   <li>{@code GET    /api/household-claims/{token}} — PUBLIC (SecurityConfig
 *       allowlist). 200 {@code OK} / 404 {@code NOT_FOUND} / 410
 *       {@code EXPIRED | REVOKED | CONSUMED | MEMBER_GONE}.</li>
 *   <li>{@code POST   /api/household-claims/{token}/accept} — signed in.
 *       200 {@code CLAIMED} (also for the same account re-accepting) / 404 / 410.</li>
 * </ul>
 */
@RestController
public class HouseholdClaimResource {

    private final HouseholdClaimService service;

    public HouseholdClaimResource(HouseholdClaimService service) {
        this.service = service;
    }

    @PostMapping("/api/households/{householdId}/manual-members/{manualMemberId}/claim-invite")
    public ResponseEntity<ClaimInvite> mint(@PathVariable String householdId,
                                            @PathVariable String manualMemberId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        ClaimInvite inv = service.mint(householdId, manualMemberId, caller);
        return ResponseEntity.status(inv.reused() ? HttpStatus.OK : HttpStatus.CREATED).body(inv);
    }

    @DeleteMapping("/api/households/{householdId}/manual-members/{manualMemberId}/claim-invite")
    public ResponseEntity<Void> revoke(@PathVariable String householdId,
                                       @PathVariable String manualMemberId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        service.revoke(householdId, manualMemberId, caller);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/household-claims/{token}")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable String token) {
        Preview p = service.resolve(token);
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "claim_household_spot");
        body.put("state", p.state().name());
        if (p.state() == State.OK) {
            body.put("householdName", p.householdName());
            body.put("memberName", p.memberName());
            body.put("band", p.band() == null ? null : p.band().name());
            body.put("inviterFirstName", p.inviterFirstName());
            body.put("expiresAt", p.expiresAt());
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(p.state() == State.NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.GONE).body(body);
    }

    @PostMapping("/api/household-claims/{token}/accept")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable String token) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "claim_household_spot");
        try {
            AcceptResult r = service.accept(token, caller);
            body.put("state", "CLAIMED");
            body.put("alreadyClaimed", r.alreadyClaimed());
            body.put("householdId", r.householdId());
            body.put("band", r.band() == null ? null : r.band().name());
            body.put("claimedName", r.claimedName());
            body.put("baseHouseholdId", r.baseHouseholdId());
            body.put("baseChanged", r.baseChanged());
            return ResponseEntity.ok(body);
        } catch (ClaimStateException e) {
            body.put("state", e.state().name());
            return ResponseEntity.status(e.getStatusCode()).body(body);
        }
    }
}
