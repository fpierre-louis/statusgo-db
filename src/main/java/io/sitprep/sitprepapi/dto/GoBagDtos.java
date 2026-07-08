package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Request and response shapes for the go-bag endpoints
 * ({@code /api/households/{id}/go-bags} + {@code /api/go-bags/{bagId}}).
 * Grouped in one file because the shapes are small and read together; matches the
 * nested-record style used by {@link PlanActivationDtos}.
 *
 * <p>The wizard is BACKEND-DRIVEN: {@link RecommendationDto} carries the
 * fully personalized, demographic-scaled checklist + strategy meta, and the
 * React wizard is a display layer over it. Product identity ships as
 * {@code productKey} strings only — affiliate links/ASINs live in the
 * frontend {@code productRegistry} (single associate-tag source of truth),
 * so no commercial URL ever originates server-side.</p>
 */
public final class GoBagDtos {

    private GoBagDtos() {}

    // -----------------------------
    // Recommendation (wizard payload)
    // -----------------------------

    public record RecommendationDto(
            DemographicSummaryDto demographic,
            ComputedNeedsDto computed,
            List<RecommendedItemDto> items,
            List<StrategyDto> strategies
    ) {}

    public record DemographicSummaryDto(
            int adults, int teens, int kids, int infants,
            int dogs, int cats, int otherPets,
            /** adults + teens + kids + infants */
            int persons,
            /** dogs + cats + otherPets */
            int petsTotal
    ) {}

    public record ComputedNeedsDto(
            /** 1 gal/person+pet/day × planDays (Ready.gov / CDC / WA EMD). */
            int waterGallons,
            /** Days of no-cook food per person (ARC/Cal OES consensus). */
            int foodDaysPerPerson,
            /** "7–10 days" — ARC/CDC band; rendered verbatim, never a number. */
            String medsDaysLabel,
            int persons,
            int petsTotal,
            int planDays
    ) {}

    public record RecommendedItemDto(
            String itemKey,
            String label,
            /** water | firstaid | power | shelter | documents | tools */
            String category,
            /** 0 = grab-list minimum, 1 = recommended, 2 = situational */
            int priority,
            int qtyRecommended,
            String unit,
            /** Seed value for expiresOn; null = never expires. */
            Integer defaultShelfLifeDays,
            /** One-line sourced guidance shown under the row. */
            String helper,
            /** Frontend productRegistry key, or null (no product mapping). */
            String productKey,
            /** null | infants | kids | pets | seniorNeeds | medicalNeeds */
            String appliesWhen
    ) {}

    public record StrategyDto(
            /** premade | diy | hybrid */
            String key,
            String title,
            String blurb,
            /** "~$200–250/person" — snapshot bands, see research doc. */
            String costBand,
            /** Frontend productRegistry keys for the strategy's kit rows. */
            List<String> productKeys,
            /** For hybrid: the augment items kits always skimp on. */
            List<String> augmentItemKeys,
            boolean recommended
    ) {}

    // -----------------------------
    // Bags
    // -----------------------------

    public record GoBagDto(
            String id,
            String householdId,
            String name,
            String kind,
            String storageLabel,
            Double lat,
            Double lng,
            String strategy,
            String premadeKitLabel,
            List<GoBagItemDto> items,
            // BE-computed rollups — the FE displays, it doesn't derive.
            int itemsPacked,
            int itemsTotal,
            int p0Packed,
            int p0Total,
            LocalDate nextExpiryOn,
            /** Items dated within 30 days (or past). */
            int expiringSoonCount,
            Instant updatedAt
    ) {}

    public record GoBagItemDto(
            String id,
            String itemKey,
            /** Frontend productRegistry key for buy links, or null. */
            String productKey,
            String label,
            String category,
            int priority,
            int qtyRecommended,
            int qtyPacked,
            String unit,
            LocalDate expiresOn,
            String notes,
            boolean packed
    ) {}

    /** Lossy summary for HouseholdPlanDto / plan-tab surfaces. */
    public record GoBagSummaryDto(
            String id,
            String name,
            String kind,
            String storageLabel,
            int itemsPacked,
            int itemsTotal,
            int expiringSoonCount
    ) {}

    // -----------------------------
    // Requests
    // -----------------------------

    public record CreateGoBagRequest(
            /** Client-supplied UUID (offline-create convention). */
            String id,
            String name,
            String kind,
            String storageLabel,
            Double lat,
            Double lng,
            String strategy,
            String premadeKitLabel,
            /** True → the recommendation engine seeds the item list. */
            boolean seedItems,
            boolean seniorNeeds,
            boolean medicalNeeds,
            /** itemKey → ISO date overrides from the wizard's expiry step. */
            Map<String, String> expirySeeds
    ) {}

    public record UpdateGoBagRequest(
            String name,
            String kind,
            String storageLabel,
            Double lat,
            Double lng,
            String strategy,
            String premadeKitLabel
    ) {}

    public record ItemPatchRequest(
            /** Null = unchanged. */
            Integer qtyPacked,
            /** ISO date; null = unchanged. Setting it re-arms the reminder. */
            String expiresOn,
            /** True → null out expiresOn (and the reminder latch). */
            Boolean clearExpiry,
            /** Null = unchanged. */
            String notes
    ) {}

    /** Re-run of the wizard — additive merge, never clobbers packed rows. */
    public record SeedItemsRequest(
            boolean seniorNeeds,
            boolean medicalNeeds,
            Map<String, String> expirySeeds
    ) {}

    public record AddItemRequest(
            /** Client-supplied UUID. */
            String id,
            String label,
            String category,
            Integer qtyRecommended,
            String unit,
            String expiresOn,
            String notes
    ) {}
}
