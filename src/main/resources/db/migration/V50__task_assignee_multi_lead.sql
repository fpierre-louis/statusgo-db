-- V50 — Multi-lead assignment model (Phase 2a of the Work-Order overhaul).
--
-- Removes the "<= 1 LEAD per task" invariant so a work order can have SEVERAL
-- leads (any group member can be a Lead or Helper), and introduces an OPTIONAL
-- "primary" lead — the single point of contact when there are several. Full
-- design + owner-locked decisions: docs/DOCS_STEP2_ROLE_MODEL_DESIGN.md and the
-- Phase-2a proposal.
--
-- WHY THIS IS ADDITIVE + LOW-RISK (NOT a V49-style reconciliation):
--   * Step 1 DROPs one index. Steps 2-3 add a column + a CHECK. Step 4 is a
--     trivial flag backfill DERIVED FROM the existing `role` column (sets
--     is_primary on today's lone LEAD rows). Step 5 adds one partial index.
--   * NOTHING is deleted and NO row identity is rewritten. Unlike V49 (which
--     DELETE'd every row and rebuilt from assignee_email), V50 only stamps a new
--     boolean and reshapes constraints. The single UPDATE reads `role` and writes
--     `is_primary` on the same rows — it cannot lose an assignment.
--   * The backfill is SAFE because V48/V49 guarantee <= 1 LEAD per task TODAY, so
--     "one primary per led task" holds the instant this runs — no task can come
--     out of the backfill with two primaries (the new partial-unique index in
--     step 5 would reject that, and by construction it can't happen).
--   * Plain transactional DDL on a tiny table — NO CREATE INDEX CONCURRENTLY, so
--     it cannot hit the prod statement_timeout trap
--     (reference_flyway_concurrently_timeout).
--
-- ORDERING (deliberate):
--   1. drop the one-LEAD index  -> multiple LEAD rows become legal
--   2. add is_primary (default false)
--   3. add the "primary must be a lead" CHECK  -> guards the backfill + all writes
--   4. backfill: today's LEAD becomes its task's primary (lone lead = trivial POC)
--   5. add the one-PRIMARY partial-unique index LAST, so it validates the
--      already-consistent post-backfill state (mirrors how V48 ordered its index
--      after its backfill).
--
-- ENTITY PARITY: the matching `is_primary` field ships on domain/TaskAssignee.java
-- with this migration so ddl-auto=validate stays satisfied at boot. The partial-
-- unique one-primary index and the CHECK are Postgres-only (not expressible as JPA
-- annotations); the H2 test profile builds the table from the entity via
-- ddl-auto=create-drop and does NOT exercise them — they are validated by the
-- local-Postgres apply + the prod rehearsal, exactly as the V48 partial index was.
--
-- ROLLBACK-IN-THINKING: reversing V50 = drop uk_task_assignee_one_primary, drop
-- the CHECK, drop the is_primary column, recreate uk_task_assignee_one_lead. That
-- recreation only succeeds while no task has two LEAD rows yet — i.e. before any
-- multi-lead WRITE goes live. So the safe rollback window is "V50 applied but the
-- multi-lead app code not yet serving writes", which is exactly the gated apply
-- sequence (apply + verify BEFORE the deploy that starts writing second leads).

-- ---------------------------------------------------------------------------
-- 1. Drop the <= 1 LEAD invariant (this is what unlocks multiple leads).
-- ---------------------------------------------------------------------------
-- Nothing in SQL depends on this index (no FK/view); the only dependency is the
-- app repo method findByPostIdAndRole, which ships as List<> in the same deploy.
DROP INDEX IF EXISTS uk_task_assignee_one_lead;

-- ---------------------------------------------------------------------------
-- 2. The optional "primary lead" marker.
-- ---------------------------------------------------------------------------
ALTER TABLE task_assignee
    ADD COLUMN is_primary BOOLEAN NOT NULL DEFAULT false;

-- ---------------------------------------------------------------------------
-- 3. A primary must be a LEAD (a Helper can never be the point of contact).
-- ---------------------------------------------------------------------------
ALTER TABLE task_assignee
    ADD CONSTRAINT ck_task_assignee_primary_is_lead
    CHECK (NOT is_primary OR role = 'LEAD');

-- ---------------------------------------------------------------------------
-- 4. Backfill — today's lone LEAD becomes its task's primary point of contact.
-- ---------------------------------------------------------------------------
-- Safe by construction: V48/V49 enforce <= 1 LEAD per task up to this migration,
-- so this sets at most one primary per task and no led task is left without one.
UPDATE task_assignee SET is_primary = true WHERE role = 'LEAD';

-- ---------------------------------------------------------------------------
-- 5. The hard, DB-enforced "<= 1 PRIMARY per task" invariant. Partial (filtered)
--    unique index — Postgres-only; cannot be a JPA @UniqueConstraint. Trades
--    places with the one-LEAD index dropped in step 1.
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_assignee_one_primary
    ON task_assignee (task_id)
    WHERE is_primary = true;
