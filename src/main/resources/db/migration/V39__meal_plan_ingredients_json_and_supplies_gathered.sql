-- V39 — Food Planner: fix per-menu ingredient persistence + add the
-- "supplies gathered" completion flag.
--
-- 1) meal_plan_v2.ingredients_json
--    MealPlan.ingredients was an @ElementCollection of Map<String, List<String>>
--    mapped onto a single VARCHAR(255) column (meal_plan_ingredients_v2.ingredients).
--    Map<String, List<String>> is NOT a legal element-collection value type
--    (JPA allows only basic/embeddable map values, never a nested collection),
--    so Hibernate silently failed to round-trip the lists and every menu came
--    back with an empty ingredients map — collapsing the shopping-list /
--    FoodPlanCalculator output to demographic baselines only. The entity now
--    serializes the whole map to this one TEXT column (same hand-rolled-JSON
--    pattern as meal_plan_data_v2.selected_items_json). The old
--    meal_plan_ingredients_v2 table is left in place (harmless, no longer
--    mapped; ddl-auto=validate ignores unreferenced tables).
--
-- 2) meal_plan_data_v2.supplies_gathered
--    Three-state Food Planner completion: a plan can exist yet the household
--    hasn't ACQUIRED the food. Nullable (an ordinary shopping-list save omits
--    it, so upsert leaves the prior value alone); read as false when null.

ALTER TABLE meal_plan_v2 ADD COLUMN IF NOT EXISTS ingredients_json TEXT;

ALTER TABLE meal_plan_data_v2 ADD COLUMN IF NOT EXISTS supplies_gathered BOOLEAN;
