package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.HouseholdAccompanimentDto;
import io.sitprep.sitprepapi.service.HouseholdAccompanimentService;
import io.sitprep.sitprepapi.service.HouseholdAccompanimentService.Ref;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * "With me" feature endpoints. Mirrors the BACKEND ASKS in
 * {@code Status Now/src/me/household/householdAccompaniments.js}.
 *
 * <pre>
 *   GET    /api/households/{id}/accompaniments
 *   POST   /api/households/{id}/accompaniments        — claim or move
 *   POST   /api/households/{id}/accompaniments/confirm — accompanied confirms
 *   DELETE /api/households/{id}/accompaniments/{kind}/{refId} — release
 * </pre>
 */
@RestController
@RequestMapping("/api/households/{householdId}/accompaniments")
public class HouseholdAccompanimentResource {

    private final HouseholdAccompanimentService service;

    public HouseholdAccompanimentResource(HouseholdAccompanimentService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<HouseholdAccompanimentDto>> list(@PathVariable String householdId) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.list(householdId));
    }

    @PostMapping
    public ResponseEntity<HouseholdAccompanimentDto> claim(
            @PathVariable String householdId,
            @RequestBody ClaimRequest body) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        boolean crisis = body != null && Boolean.TRUE.equals(body.crisisOverride());
        return ResponseEntity.ok(
                service.claim(householdId, actor, body.supervisorRef(), body.accompaniedRef(), crisis));
    }

    @PostMapping("/confirm")
    public ResponseEntity<HouseholdAccompanimentDto> confirm(
            @PathVariable String householdId,
            @RequestBody ConfirmRequest body) {
        AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(
                service.confirm(householdId, body.accompaniedKind(), body.accompaniedId()));
    }

    @DeleteMapping("/{kind}/{refId}")
    public ResponseEntity<Void> release(
            @PathVariable String householdId,
            @PathVariable String kind,
            @PathVariable String refId) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        service.release(householdId, actor, kind, refId);
        return ResponseEntity.noContent().build();
    }

    public record ClaimRequest(Ref supervisorRef, Ref accompaniedRef, Boolean crisisOverride) {}
    public record ConfirmRequest(String accompaniedKind, String accompaniedId) {}
}
