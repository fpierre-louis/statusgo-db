package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.GoBag;
import io.sitprep.sitprepapi.dto.GoBagDtos.AddItemRequest;
import io.sitprep.sitprepapi.dto.GoBagDtos.CreateGoBagRequest;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagItemDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.ItemPatchRequest;
import io.sitprep.sitprepapi.dto.GoBagDtos.RecommendationDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.SeedItemsRequest;
import io.sitprep.sitprepapi.dto.GoBagDtos.UpdateGoBagRequest;
import io.sitprep.sitprepapi.service.GoBagRecommendationService;
import io.sitprep.sitprepapi.service.GoBagService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import io.sitprep.sitprepapi.web.Idempotent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Go-bag CRUD + the backend-driven recommendation payload.
 *
 * <p>Auth: reads gated to household members
 * ({@link HouseholdAccessService#requireCanReadHousehold}); writes to
 * owner/admins ({@link HouseholdAccessService#requireCanAdminHousehold}).
 * The bag-scoped routes resolve the bag's household first, then apply the
 * same gate — so a member of household A can't mutate household B's bag.
 * Caller identity always comes from {@link AuthUtils#requireAuthenticatedEmail()};
 * request-body emails are never trusted (EvacuationPlanResource precedent).</p>
 */
@RestController
@CrossOrigin(origins = "http://localhost:3000")
public class GoBagResource {

    private final GoBagService goBagService;
    private final GoBagRecommendationService recommendationService;
    private final HouseholdAccessService access;

    public GoBagResource(GoBagService goBagService,
                         GoBagRecommendationService recommendationService,
                         HouseholdAccessService access) {
        this.goBagService = goBagService;
        this.recommendationService = recommendationService;
        this.access = access;
    }

    // ---------------------------------------------------------------------
    // Household-scoped
    // ---------------------------------------------------------------------

    @GetMapping("/api/households/{householdId}/go-bags")
    public ResponseEntity<List<GoBagDto>> list(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(goBagService.listForHousehold(householdId));
    }

    /**
     * The Guided Assistant payload: demographic-scaled checklist + Buy/Build/
     * Hybrid strategy meta. The FE wizard renders this verbatim — it does no
     * scaling math. 409 when the household has no demographics yet.
     */
    @GetMapping("/api/households/{householdId}/go-bags/recommendation")
    public ResponseEntity<RecommendationDto> recommendation(
            @PathVariable String householdId,
            @RequestParam(defaultValue = "false") boolean seniorNeeds,
            @RequestParam(defaultValue = "false") boolean medicalNeeds) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(
                recommendationService.recommendForHousehold(householdId, seniorNeeds, medicalNeeds));
    }

    @PostMapping("/api/households/{householdId}/go-bags")
    @Idempotent
    public ResponseEntity<GoBagDto> create(@PathVariable String householdId,
                                           @RequestBody CreateGoBagRequest req) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanAdminHousehold(caller, householdId);
        return ResponseEntity.ok(goBagService.createBag(caller, householdId, req));
    }

    // ---------------------------------------------------------------------
    // Bag-scoped (resolve household from the bag, then gate)
    // ---------------------------------------------------------------------

    @PutMapping("/api/go-bags/{bagId}")
    public ResponseEntity<GoBagDto> update(@PathVariable String bagId,
                                           @RequestBody UpdateGoBagRequest req) {
        requireAdminOfBag(bagId);
        return ResponseEntity.ok(goBagService.updateBag(bagId, req));
    }

    @DeleteMapping("/api/go-bags/{bagId}")
    public ResponseEntity<Void> delete(@PathVariable String bagId) {
        requireAdminOfBag(bagId);
        goBagService.deleteBag(bagId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/go-bags/{bagId}/items/seed")
    public ResponseEntity<GoBagDto> seed(@PathVariable String bagId,
                                         @RequestBody(required = false) SeedItemsRequest req) {
        requireAdminOfBag(bagId);
        return ResponseEntity.ok(goBagService.seedItems(bagId, req));
    }

    @PostMapping("/api/go-bags/{bagId}/items")
    public ResponseEntity<GoBagItemDto> addItem(@PathVariable String bagId,
                                                @RequestBody AddItemRequest req) {
        requireAdminOfBag(bagId);
        return ResponseEntity.ok(goBagService.addItem(bagId, req));
    }

    @PatchMapping("/api/go-bags/{bagId}/items/{itemId}")
    public ResponseEntity<GoBagItemDto> patchItem(@PathVariable String bagId,
                                                  @PathVariable String itemId,
                                                  @RequestBody ItemPatchRequest req) {
        requireAdminOfBag(bagId);
        return ResponseEntity.ok(goBagService.patchItem(bagId, itemId, req));
    }

    /** "Mark refreshed" — bump expiry to today + shelf life, re-arm reminder. */
    @PostMapping("/api/go-bags/{bagId}/items/{itemId}/refresh")
    public ResponseEntity<GoBagItemDto> refreshItem(@PathVariable String bagId,
                                                    @PathVariable String itemId) {
        requireAdminOfBag(bagId);
        return ResponseEntity.ok(goBagService.markRefreshed(bagId, itemId));
    }

    @DeleteMapping("/api/go-bags/{bagId}/items/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable String bagId,
                                           @PathVariable String itemId) {
        requireAdminOfBag(bagId);
        goBagService.deleteItem(bagId, itemId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------

    /** Loads the bag (404 if missing) and asserts caller admins its household. */
    private void requireAdminOfBag(String bagId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        GoBag bag = goBagService.requireBag(bagId);
        access.requireCanAdminHousehold(caller, bag.getHouseholdId());
    }
}
