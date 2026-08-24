package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.HouseholdAccompanimentDto;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
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
 *
 * <p><b>Household membership is required on every route (2026-08-24).</b> These
 * endpoints previously checked only that the caller was signed in. Passing
 * someone else's household id let an outsider read who is with whom, insert
 * themselves as the supervising adult for another family's child, or release a
 * real guardian's claim — during a live crisis, which is exactly when this
 * feature is read.</p>
 *
 * <p>The gate is membership, not admin, on writes too: an outsider is the
 * boundary that was broken, and whether a non-admin member may claim is an
 * existing intra-household rule (the service already receives the actor) that
 * should not change silently here.</p>
 */
@RestController
@RequestMapping("/api/households/{householdId}/accompaniments")
public class HouseholdAccompanimentResource {

    private final HouseholdAccompanimentService service;
    private final HouseholdAccessService access;

    public HouseholdAccompanimentResource(HouseholdAccompanimentService service,
                                          HouseholdAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ResponseEntity<List<HouseholdAccompanimentDto>> list(@PathVariable String householdId) {
        access.requireCanReadHousehold(AuthUtils.requireAuthenticatedEmail(), householdId);
        return ResponseEntity.ok(service.list(householdId));
    }

    @PostMapping
    public ResponseEntity<HouseholdAccompanimentDto> claim(
            @PathVariable String householdId,
            @RequestBody ClaimRequest body) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(actor, householdId);
        boolean crisis = body != null && Boolean.TRUE.equals(body.crisisOverride());
        return ResponseEntity.ok(
                service.claim(householdId, actor, body.supervisorRef(), body.accompaniedRef(), crisis));
    }

    @PostMapping("/confirm")
    public ResponseEntity<HouseholdAccompanimentDto> confirm(
            @PathVariable String householdId,
            @RequestBody ConfirmRequest body) {
        access.requireCanReadHousehold(AuthUtils.requireAuthenticatedEmail(), householdId);
        return ResponseEntity.ok(
                service.confirm(householdId, body.accompaniedKind(), body.accompaniedId()));
    }

    @DeleteMapping("/{kind}/{refId}")
    public ResponseEntity<Void> release(
            @PathVariable String householdId,
            @PathVariable String kind,
            @PathVariable String refId) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(actor, householdId);
        service.release(householdId, actor, kind, refId);
        return ResponseEntity.noContent().build();
    }

    public record ClaimRequest(Ref supervisorRef, Ref accompaniedRef, Boolean crisisOverride) {}
    public record ConfirmRequest(String accompaniedKind, String accompaniedId) {}
}
