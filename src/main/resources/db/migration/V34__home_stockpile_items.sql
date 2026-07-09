-- 14-Day At-Home Stockpile — persistent per-household check-off state for the
-- NON-FOOD stockpile items (Sanitation / Power & Heat / Medical / Tools & Safety).
--
-- A row's mere existence == "on hand". Toggling an item inserts or deletes the
-- row; there is no boolean column. The (household_id, item_key) unique constraint
-- keeps a check idempotent and is the index that backs findByHouseholdId (its
-- leading column is household_id).
--
-- Food & Water is deliberately NOT tracked here — it stays DERIVED from the Food
-- Planner (see HomeStockpileService.hasRealFourteenDayFoodPlan). Only the
-- ~28 stockpile-* non-food keys are ever written; the service rejects any other
-- key (go-bag keys, food_water keys) at the endpoint.
--
-- Decoupling (T-15): this is its OWN table — never go_bag_item — so a go-bag
-- check can never fulfil a stockpile item and vice-versa.
CREATE TABLE IF NOT EXISTS home_stockpile_item (
    id           BIGSERIAL   PRIMARY KEY,
    household_id VARCHAR(64)  NOT NULL,
    item_key     VARCHAR(80)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    CONSTRAINT uq_home_stockpile_item UNIQUE (household_id, item_key)
);
