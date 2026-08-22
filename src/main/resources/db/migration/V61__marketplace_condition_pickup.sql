-- Marketplace — condition and pickup.
--
-- Two facts the listing card and the thread's fact block both asked for and
-- neither could render. `stripStatusFor` on the FE already documented the gap
-- in its marketplace case: "The handoff spec asks for price · availability ·
-- pickup; two of those three do not exist" — this closes the two that are
-- genuinely about the item rather than about inventory state.
--
-- BOTH NULLABLE, and the composer question is deliberately NOT answered here.
-- Whether the composer REQUIRES them is a product decision (a required field
-- is a listing someone abandons; an optional one is a fact block with holes),
-- and it is raised in the report rather than settled in a migration. The
-- schema supports either — a later NOT NULL is a one-line follow-up, whereas
-- shipping NOT NULL now and relaxing it costs a second migration and a
-- backfill.
--
-- `condition` is a free-form short string, not an enum. The composer will
-- offer chips, but the ResourceCategory lesson is that a vocabulary belongs in
-- ONE place that both writers read — and that place is Java, where
-- ResourceCategory already lives, not a Postgres CHECK that a second writer
-- cannot see. When the chip set is decided it goes next to ResourceCategory.
--
-- ROW COUNT MEASURED BEFORE APPLYING: `task` = 12,133 rows, of which
-- kind='marketplace' = ZERO. Two nullable adds, no backfill, no rewrite.

ALTER TABLE task ADD COLUMN IF NOT EXISTS item_condition VARCHAR(40);
ALTER TABLE task ADD COLUMN IF NOT EXISTS pickup_note   VARCHAR(160);

-- `item_condition`, not `condition` — CONDITION is a reserved word in SQL and
-- a plain `condition` column forces quoting in every hand-written query
-- forever. The Java field stays `condition`; @Column carries the mapping.
COMMENT ON COLUMN task.item_condition IS
    'Marketplace: free-form item condition ("Like new"). Vocabulary lives in Java.';
COMMENT ON COLUMN task.pickup_note IS
    'Marketplace: where/how to collect ("Porch, 400 N & Main").';
