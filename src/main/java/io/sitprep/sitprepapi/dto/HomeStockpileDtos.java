package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response shapes for the <b>14-Day At-Home Stockpile</b> — the ADVANCED-tier,
 * stay-at-home survival reserve that the Red Cross treats as distinct from the
 * 3-day portable Go Bag.
 *
 * <p><b>Thick-server, thin-client.</b> {@code HomeStockpileService} reads the
 * household {@link io.sitprep.sitprepapi.domain.Demographic} + the existing
 * {@link io.sitprep.sitprepapi.service.FoodPlanCalculatorService} output and
 * returns fully-computed, demographic-scaled categories. The React
 * "Advanced Readiness" surface renders this verbatim — it owns none of the
 * quantity math.</p>
 *
 * <p><b>Two deliberate architecture rules encoded here:</b></p>
 * <ol>
 *   <li><b>Never penalizes baseline readiness.</b> This DTO ships on its own
 *       endpoint and is NOT wired into {@code HouseholdReadinessDto.pillarScores}
 *       / {@code readinessPercent}. Missing stockpile items can never lower the
 *       4-pillar baseline score. {@code tier == "ADVANCED"} makes that explicit.</li>
 *   <li><b>Decoupled from the Go Bag.</b> Every {@code itemKey} is namespaced
 *       {@code stockpile-*} — disjoint from the Go Bag's bare-kebab item keys
 *       ({@code water}, {@code noaa-radio}, {@code first-aid-kit}) and its
 *       {@code gobag-*} product keys. Checking a go-bag item can never fulfil a
 *       stockpile item, and vice-versa.</li>
 * </ol>
 *
 * <p><b>Food &amp; Water is not duplicated.</b> The {@code food_water} category
 * carries no quantities of its own — its {@code satisfied} flag is DERIVED from
 * {@code FoodPlanCalculatorService} (a real 14-day food plan exists), and it
 * deep-links to the Food Planner ({@code /create-foodsupply}) when unmet.</p>
 */
public final class HomeStockpileDtos {

    private HomeStockpileDtos() {}

    public record HomeStockpileDto(
            /** Supply context discriminator — "stay_home_14_day". */
            String context,
            /** "ADVANCED" — long-term goal; never affects baseline readinessPercent. */
            String tier,
            String title,
            String summary,
            /** Target horizon in days — 14. */
            int planDays,
            /** Household size used for demographic scaling (min 1). */
            int persons,
            /**
             * Completion across EVERY item: Food &amp; Water's two DERIVED lines
             * (from the Food Planner) plus each checked-off non-food item
             * (persisted per household in {@code home_stockpile_item}). Rounded
             * percent; the optimistic FE recomputes it with the same formula.
             */
            int percentComplete,
            /** Denormalized convenience: the food_water category's derived state. */
            boolean foodWaterSatisfied,
            List<StockpileCategoryDto> categories,
            Instant generatedAt,
            String datasetVersion
    ) {}

    public record StockpileCategoryDto(
            /** food_water | sanitation | power_heat | medical | tools_safety */
            String key,
            String label,
            String blurb,
            /** Whether this category counts toward completion — now true for all (food_water derived, non-food persisted). */
            boolean trackable,
            boolean satisfied,
            /** Non-null only on food_water when unmet — the cross-link CTA label. */
            String cta,
            /** Non-null only on food_water when unmet — "/create-foodsupply". */
            String route,
            List<StockpileItemDto> items
    ) {}

    public record StockpileItemDto(
            /** Namespaced "stockpile-*" — never collides with Go Bag item/product keys. */
            String itemKey,
            String label,
            /** Demographic-scaled recommended quantity (0 for food_water pointers). */
            int qtyRecommended,
            String unit,
            /** 0 = essential, 1 = recommended, 2 = situational. */
            int priority,
            String detail,
            boolean satisfied
    ) {}
}
