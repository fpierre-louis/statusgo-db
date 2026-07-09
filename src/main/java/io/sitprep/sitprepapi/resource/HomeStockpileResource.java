package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.HomeStockpileDtos.HomeStockpileDto;
import io.sitprep.sitprepapi.service.HomeStockpileService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 14-Day At-Home Stockpile (ADVANCED tier). Returns the fully-computed,
 * demographic-scaled stockpile for a household — the FE renders it with no
 * client-side supply math. Read-gated to household members, exactly like
 * {@link FoodPlanResource}.
 *
 * <p>This is a standalone advisory surface: it is intentionally NOT part of the
 * {@code /api/me} readiness payload, guaranteeing the 14-day stockpile can never
 * lower a household's baseline 4-pillar {@code readinessPercent}.</p>
 */
@RestController
@CrossOrigin(origins = "http://localhost:3000")
public class HomeStockpileResource {

    private final HomeStockpileService stockpileService;
    private final HouseholdAccessService access;

    public HomeStockpileResource(HomeStockpileService stockpileService,
                                 HouseholdAccessService access) {
        this.stockpileService = stockpileService;
        this.access = access;
    }

    @GetMapping("/api/households/{householdId}/supplies/home-stockpile")
    public ResponseEntity<HomeStockpileDto> homeStockpile(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(stockpileService.getForHousehold(householdId));
    }

    /**
     * Toggle a non-food item's "on hand" state (insert/delete the check row) and
     * return the recomputed stockpile. Member-write: the stockpile is shared,
     * non-destructive household inventory that any member can help build (the card
     * renders for every member, not just admins). The service rejects any key that
     * isn't a checkable non-food item (Food &amp; Water keys, go-bag keys) with 400.
     */
    @PostMapping("/api/households/{householdId}/supplies/home-stockpile/items/{itemKey}")
    public ResponseEntity<HomeStockpileDto> toggleItem(@PathVariable String householdId,
                                                       @PathVariable String itemKey) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(stockpileService.toggleItem(householdId, itemKey));
    }
}
