-- V42 — Drop the dead pre-"v2" legacy Food Planner tables.
--
-- The meal-plan model was renamed to the *_v2 tables long ago; the current
-- entities map ONLY meal_plan_v2 / meal_plan_data_v2 / meal_plan_meals_v2 /
-- ingredient_v2. These pre-rename tables are unmapped and unreferenced by any
-- entity, native query, or bootstrap runner (grep-verified), holding only stale
-- pre-v2 data. Removing dead legacy tables outright per the standing pre-launch
-- policy (no back-compat burden; see SYSTEM_TRAPS_AND_PATTERNS.md).
--
-- Dropped children-first to respect the legacy FK chain
-- (meal_plan_meals/meal_plan_ingredients -> meal_plan -> meal_plan_data;
-- ingredient stands alone). IF EXISTS = idempotent / safe on fresh DBs.

DROP TABLE IF EXISTS meal_plan_meals;
DROP TABLE IF EXISTS meal_plan_ingredients;
DROP TABLE IF EXISTS meal_plan;
DROP TABLE IF EXISTS meal_plan_data;
DROP TABLE IF EXISTS ingredient;
