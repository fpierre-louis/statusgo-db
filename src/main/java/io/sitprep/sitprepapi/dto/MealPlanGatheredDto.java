package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.MealPlanData;

/**
 * Response of {@code PATCH /api/mealPlans/supplies-gathered}: the row that was
 * marked and the flag as stored. {@link MealPlanDto} doesn't carry the flag (it
 * reaches the client through {@code MePlansDto.MealPlanSummary}), so the narrow
 * write answers with exactly what it changed.
 */
public record MealPlanGatheredDto(Long id, boolean suppliesGathered) {
    public static MealPlanGatheredDto from(MealPlanData m) {
        return new MealPlanGatheredDto(m.getId(), Boolean.TRUE.equals(m.getSuppliesGathered()));
    }
}
