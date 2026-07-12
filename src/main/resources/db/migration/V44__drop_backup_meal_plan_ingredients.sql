-- V44 — Drop the temporary safety-net backup table.
-- (Numbered V44, not V43, to avoid colliding with the parallel
-- V43__unified_workorder_schema.sql migration.)
--
-- V41 dropped the orphaned meal_plan_ingredients_v2 (whose NO-ACTION FK to
-- meal_plan_v2 caused the meal-plan-update 500). Before that drop, its 80 dead
-- pre-jsonb rows were copied into _backup_meal_plan_ingredients_v2 as a
-- recovery net while the fix was verified end-to-end. The "supplies gathered"
-- flow is now confirmed working (no 500; dashboard flips green), so the backup
-- is no longer needed. IF EXISTS = idempotent / safe on fresh DBs.

DROP TABLE IF EXISTS _backup_meal_plan_ingredients_v2;
