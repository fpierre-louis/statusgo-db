package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;

import java.util.List;
import java.util.Map;

/**
 * Read shape for {@link MealPlanData} (Thin-Client Refactor Phase 3 — DTO
 * hardening). Strips {@code ownerEmail} + {@code householdId} and, on each
 * menu, the JPA back-reference to the parent (the entity guarded that
 * recursion with {@code @JsonManagedReference}/{@code @JsonBackReference}).
 *
 * <p>{@code selectedItemsJson} is intentionally <b>retained</b> — the FE parses
 * it to restore the user's item picks (PrintableFoodMenu, MenuBuilderStep) —
 * but it now ships inside a controlled DTO instead of a raw entity that also
 * leaked owner/household internals.</p>
 */
public record MealPlanDto(
        Long id,
        List<MenuDto> mealPlan,
        PlanDurationDto planDuration,
        int numberOfMenuOptions,
        String selectedItemsJson
) {
    public static MealPlanDto from(MealPlanData m) {
        List<MenuDto> menus = m.getMealPlan() == null ? List.of()
                : m.getMealPlan().stream().map(MenuDto::from).toList();
        PlanDurationDto duration = m.getPlanDuration() == null ? null
                : new PlanDurationDto(
                        m.getPlanDuration().getQuantity(),
                        m.getPlanDuration().getUnit());
        return new MealPlanDto(
                m.getId(),
                menus,
                duration,
                m.getNumberOfMenuOptions(),
                m.getSelectedItemsJson());
    }

    public record MenuDto(
            Long id,
            Map<String, String> meals,
            Map<String, List<String>> ingredients
    ) {
        public static MenuDto from(MealPlan mp) {
            return new MenuDto(mp.getId(), mp.getMeals(), mp.getIngredients());
        }
    }

    public record PlanDurationDto(int quantity, String unit) {}
}
