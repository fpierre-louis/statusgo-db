package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.GhostTenantService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Ghost Tenant claim endpoints (Phase 3). Kept separate from GroupResource so
 * the claim engine's wiring is isolated. Base path is still {@code /api/groups}.
 */
@RestController
@RequestMapping("/api/groups")
public class GhostTenantResource {

    private final GhostTenantService ghostTenantService;

    public GhostTenantResource(GhostTenantService ghostTenantService) {
        this.ghostTenantService = ghostTenantService;
    }

    /**
     * Register the authenticated resident's demand for an unclaimed (GHOST)
     * group. Idempotent per resident (one +1 per person, enforced by the
     * demand-vote ledger). Returns the group's updated demand signal.
     *
     * <ul>
     *   <li>200 — counted (or already counted); body has the current signal.</li>
     *   <li>409 — the group is not in the GHOST claim state.</li>
     *   <li>404 — no such group.</li>
     * </ul>
     */
    @PostMapping("/{groupId}/demand-signal")
    public ResponseEntity<?> demandSignal(@PathVariable String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        try {
            int signal = ghostTenantService.recordDemandSignal(groupId, caller);
            return ResponseEntity.ok(Map.of(
                    "groupId", groupId,
                    "ghostDemandSignal", signal));
        } catch (IllegalStateException notGhost) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "GROUP_NOT_GHOST", "message", notGhost.getMessage()));
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
