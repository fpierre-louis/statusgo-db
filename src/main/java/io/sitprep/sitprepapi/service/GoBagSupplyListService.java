package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.GoBag;
import io.sitprep.sitprepapi.domain.GoBagItem;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagShoppingListDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.ShoppingItemDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.SupplyCountsDto;
import io.sitprep.sitprepapi.repo.AlertModeStateRepo;
import io.sitprep.sitprepapi.repo.GoBagItemRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The procurement engine behind "Complete this bag" (preparedness-completion
 * framing, not a storefront): filters a bag's checklist down to what's still
 * missing ({@code toBuy}) or due for rotation ({@code toReplace}), joins each
 * row against {@link GoBagProductCatalog} for FINAL retailer URLs, and decides
 * commerce suppression server-side. The FE renders — zero filtering, zero URL
 * construction, zero math.
 *
 * <p><b>Suppression (Decision D):</b> links are nulled (payload still ships as
 * action guidance) when any of, in precedence order:</p>
 * <ol>
 *   <li>{@code household_checkin} — the household's check-in is Active;</li>
 *   <li>{@code deployed_plan} — the household owner has an unexpired plan
 *       activation;</li>
 *   <li>{@code area_alert} — the persisted {@link io.sitprep.sitprepapi.domain.AlertModeState}
 *       for the household's zip bucket is {@code alert}/{@code crisis}. Read
 *       straight from the state table by PK — deliberately NOT
 *       {@code AlertModeService.getForLatLng}, which reverse-geocodes via
 *       Nominatim on the request thread (rate-limited network call). A missing
 *       row means calm, same semantics as the lazy-create-on-read design.</li>
 * </ol>
 */
@Service
public class GoBagSupplyListService {

    private final GoBagService goBagService;
    private final GoBagItemRepo itemRepo;
    private final GoBagProductCatalog catalog;
    private final GroupRepo groupRepo;
    private final PlanActivationRepo activationRepo;
    private final AlertModeStateRepo alertModeRepo;

    public GoBagSupplyListService(GoBagService goBagService,
                                  GoBagItemRepo itemRepo,
                                  GoBagProductCatalog catalog,
                                  GroupRepo groupRepo,
                                  PlanActivationRepo activationRepo,
                                  AlertModeStateRepo alertModeRepo) {
        this.goBagService = goBagService;
        this.itemRepo = itemRepo;
        this.catalog = catalog;
        this.groupRepo = groupRepo;
        this.activationRepo = activationRepo;
        this.alertModeRepo = alertModeRepo;
    }

    @Transactional(readOnly = true)
    public GoBagShoppingListDto supplyList(String bagId) {
        GoBag bag = goBagService.requireBag(bagId);
        List<GoBagItem> items = itemRepo.findByBagIdOrderByPriorityAscCreatedAtAsc(bagId);
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(GoBagRecommendationService.EXPIRY_WARNING_DAYS);

        String reason = suppressionReason(bag.getHouseholdId());
        boolean suppressed = reason != null;

        List<ShoppingItemDto> toBuy = items.stream()
                .filter(i -> remaining(i) > 0)
                .sorted(Comparator.comparingInt(GoBagItem::getPriority)
                        .thenComparing(GoBagItem::getLabel,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(i -> toRow(i, remaining(i), today, suppressed))
                .toList();

        // Fully packed but dated within the horizon (or past): rotation needs.
        // Sorted by expiresOn ascending, so expired items lead naturally.
        List<ShoppingItemDto> toReplace = items.stream()
                .filter(i -> remaining(i) == 0
                        && i.getExpiresOn() != null
                        && !i.getExpiresOn().isAfter(horizon))
                .sorted(Comparator.comparing(GoBagItem::getExpiresOn))
                .map(i -> toRow(i, Math.max(1, i.getQtyRecommended()), today, suppressed))
                .toList();

        int linkable = (int) Stream.concat(toBuy.stream(), toReplace.stream())
                .filter(r -> r.productKey() != null && catalog.byKey(r.productKey()).isPresent())
                .count();

        return new GoBagShoppingListDto(
                bag.getId(), bag.getName(), bag.getStorageLabel(),
                suppressed, reason,
                toBuy, toReplace,
                new SupplyCountsDto(toBuy.size(), toReplace.size(), linkable),
                Instant.now());
    }

    // ---------------------------------------------------------------------

    /** Same packed semantics as GoBagService.isPacked, expressed as a remainder. */
    private static int remaining(GoBagItem i) {
        return Math.max(0, Math.max(1, i.getQtyRecommended()) - i.getQtyPacked());
    }

    private ShoppingItemDto toRow(GoBagItem i, int qtyRemaining, LocalDate today,
                                  boolean suppressed) {
        String amazonUrl = null;
        String walmartUrl = null;
        if (!suppressed) {
            var product = catalog.byKey(i.getProductKey());
            if (product.isPresent()) {
                amazonUrl = product.get().amazonUrl();
                walmartUrl = product.get().walmartUrl();
            }
        }
        boolean expired = i.getExpiresOn() != null && i.getExpiresOn().isBefore(today);
        return new ShoppingItemDto(
                i.getId(), i.getItemKey(), i.getLabel(), i.getCategory(), i.getPriority(),
                qtyRemaining, i.getUnit(), i.getExpiresOn(), expired,
                i.getProductKey(), amazonUrl, walmartUrl);
    }

    /**
     * First matching suppression reason, or null when commerce may render.
     * Package-visible for direct unit coverage.
     */
    String suppressionReason(String householdId) {
        Group household = groupRepo.findByGroupId(householdId).orElse(null);
        if (household == null) return null;

        if ("Active".equalsIgnoreCase(household.getAlert())) return "household_checkin";

        String owner = household.getOwnerEmail();
        if (owner != null && activationRepo
                .findFirstActiveByOwnerEmail(owner, Instant.now()).isPresent()) {
            return "deployed_plan";
        }

        String zip = household.getZipCode();
        if (zip != null && zip.trim().length() >= 3) {
            String state = alertModeRepo.findById(zip.trim().substring(0, 3))
                    .map(s -> s.getState())
                    .orElse(null);
            if (AlertModeService.ALERT.equals(state) || AlertModeService.CRISIS.equals(state)) {
                return "area_alert";
            }
        }
        return null;
    }
}
