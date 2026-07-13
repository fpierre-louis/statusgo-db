-- V47 — Add the work_details JSONB bag + the need_type discriminator to the
-- task/post table.
--
-- The civic / relief WorkOrderWizard's "Site & Triage" step captures
-- need-type-specific intake fields that vary by needType (tree removal ->
-- number of trees; flooding -> water level + electrical; food/supplies ->
-- occupancy + dietary notes; other -> free description) plus a general
-- hazard note. The FLAT, stable triage columns (near_power_lines,
-- electrical_hazard, water_level, safe_to_enter) already exist from V45;
-- this column holds only the SPARSE, shape-varying remainder.
--
-- Why jsonb over a dozen nullable columns: the field set differs per
-- needType and will keep evolving as later wizard phases land. A single
-- jsonb bag absorbs that churn with ZERO further migrations, gives us
-- validation-on-write + canonical storage, and stays queryable/indexable
-- if we ever need to report on it. Mirrors the meal_plan_v2.ingredients_json
-- native-JSONB mapping promoted in V40 (@JdbcTypeCode(SqlTypes.JSON) +
-- columnDefinition = "jsonb" on Post.workDetails).
--
-- ADDITIVE + idempotent (ADD COLUMN IF NOT EXISTS) — no drops, no renames,
-- no default. Nullable: NULL on every personal task and every non-work-order
-- kind (the common case), a JSON object only on group/civic work orders that
-- ran the triage step.
--
-- Postgres-only DDL. It never runs under the H2 test profile
-- (spring.flyway.enabled=false there; Hibernate builds the H2 schema from
-- the entity via ddl-auto=create-drop, where the same
-- columnDefinition = "jsonb" is emitted and H2's PostgreSQL mode accepts it).
--
-- Plain transactional DDL on a small column add — no CREATE INDEX
-- CONCURRENTLY, so it cannot hit the prod statement_timeout trap
-- (reference_flyway_concurrently_timeout).

ALTER TABLE task ADD COLUMN IF NOT EXISTS work_details jsonb;

-- Denormalized need-type discriminator. It is ALSO carried inside work_details
-- (as work_details->>'needType') for self-description, but promoted to its own
-- first-class column so dispatch / triage queries filter on a B-tree index
-- rather than a jsonb path lookup — the "column for indexing + bag for
-- self-description" rule in EXECUTION_GAME_PLAN_WIZARD.md §3.1. Nullable: NULL
-- on personal tasks and every non-work-order kind (the common case). String
-- codes from the wizard need-type vocabulary (tree_debris | flood_water |
-- roof_structural | hazmat_utility | civic_hazard | rescue_welfare |
-- animal_rescue | other) — kept as VARCHAR (not a PG enum) so the taxonomy can
-- grow without a type migration; the app validates the vocabulary on write.
ALTER TABLE task ADD COLUMN IF NOT EXISTS need_type varchar(32);

-- PARTIAL index on non-null need_type only: the task table is dominated by
-- community-feed posts (need_type IS NULL), so a partial index stays tiny and
-- only covers the work-order rows dispatch actually filters. Plain transactional
-- CREATE INDEX (small table, pre-launch) — NOT CONCURRENTLY, so it cannot hit
-- the prod statement_timeout trap (reference_flyway_concurrently_timeout).
CREATE INDEX IF NOT EXISTS idx_task_need_type ON task (need_type) WHERE need_type IS NOT NULL;
