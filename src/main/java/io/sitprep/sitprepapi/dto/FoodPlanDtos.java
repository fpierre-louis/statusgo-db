package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response shapes for the backend-driven Food Planner (Thin-Client Refactor
 * Phase 2). {@code FoodPlanRecommendationService} reads the household's
 * demographic + saved menu + product catalog and returns a fully-computed
 * {@link FoodPlanRecommendationDto}; the React Food Planner renders it with
 * no client-side serving-size or package math.
 *
 * <p>Static product metadata (buy links, ASINs, imagery, category) stays
 * FE-owned in {@code productRegistry.js} — the FE enriches each item by name.
 * This DTO carries only the COMPUTED values (quantities, package counts) +
 * the identifiers the FE needs to join.</p>
 */
public final class FoodPlanDtos {

    private FoodPlanDtos() {}

    public record FoodPlanRecommendationDto(
            DemographicSummaryDto demographic,
            /** Total days the plan covers (duration normalized to days). */
            int planDays,
            PlanDurationDto planDuration,
            /** Computed shopping list, priority-sorted, totalRaw &gt; 0 only. */
            List<FoodPlanItemDto> items,
            MetaDto meta
    ) {}

    public record DemographicSummaryDto(
            int adults, int teens, int kids, int infants,
            int dogs, int cats, int pets,
            /** adults + teens + kids + infants */
            int persons
    ) {}

    public record PlanDurationDto(int quantity, String unit) {}

    public record FoodPlanItemDto(
            String item,
            /** Consumption unit for the raw total ("gal", "oz", "bars", "days"). */
            String unit,
            /** Raw quantity needed, in {@code unit} (the shopping-list figure). */
            double totalRaw,
            /** Packages to buy = ceil(convert(totalRaw, unit → packageUnit) / packageSize). */
            int packagesNeeded,
            double packageSize,
            String packageUnit,
            String packageLabel,
            /** 1 = Water, 2 = Baby Formula, 3 = Baby Food, 99 = everything else. */
            int priority,
            /** Per-demographic contribution, groups with total &gt; 0. */
            List<BreakdownDto> breakdown
    ) {}

    public record BreakdownDto(
            /** "adults" | "teens" | "kids" | "infants" | "dogs" | "cats" | "pets" */
            String group,
            int count,
            double total
    ) {}

    public record MetaDto(Instant generatedAt, String catalogVersion) {}
}
