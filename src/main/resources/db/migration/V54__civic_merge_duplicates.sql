-- V54 — Civic epic Slice 3: merge duplicate civic reports.
--
-- Lets a CLAIMING agency consolidate N duplicate civic reports into one
-- canonical report. The merge is a single self-link on `task`
-- (merged_into_post_id) plus a small audit (merged_at / merged_by_email) —
-- NO separate merge-event table (locked decision 2). Merged status is
-- READ-THROUGH (decision 1): the duplicate keeps its own civic_status frozen
-- for history, and every read surface resolves merged_into_post_id -> the
-- survivor's status/note for DISPLAY. One source of truth, no write-fanout.
--
-- SAFETY / SHAPE (mirrors V51/V53 philosophy):
--   * PURELY ADDITIVE — three nullable columns + one FK + one CHECK + one
--     btree index. NO backfill, NO UPDATE, NO DELETE — zero existing rows are
--     touched. Every existing task keeps merged_into_post_id = NULL and behaves
--     exactly as before.
--   * Plain transactional DDL on the (already-large) task table — NO CREATE
--     INDEX CONCURRENTLY (dodges the prod statement_timeout trap; the index is
--     on a single nullable bigint so the brief ACCESS EXCLUSIVE lock to add the
--     column + build the index is short).
--   * FK merged_into_post_id -> task(id) ON DELETE SET NULL — if a survivor is
--     ever deleted, its duplicates DETACH to standalone reports, never
--     cascade-destroyed (citizen civic data is never destroyed; mirrors V53's
--     ON DELETE philosophy for the report itself).
--   * CHECK (merged_into_post_id IS NULL OR merged_into_post_id <> id) — a row
--     can never merge into itself. Chains are additionally flattened in the
--     service (every duplicate points DIRECTLY at a canonical whose own
--     merged_into_post_id IS NULL), so cycles are impossible by construction.
--   * tag_source='merged' (decision 4b, union'd agency tags) needs no schema
--     change — civic_report_agency.tag_source is already a free VARCHAR(16).
--
-- VERSION: prod is at V53 (flyway_schema_history). V54 is the next version with
-- no collision. The H2 test profile builds `task` from the Post entity via
-- ddl-auto=create-drop and does NOT exercise this FK/CHECK — the service guards
-- (self-merge filtered, flatten-on-merge, cross-agency-claim 409) are the
-- test-profile enforcement, same arrangement V50/V53 use for Postgres-only
-- constraints.

-- 1. The merge link + audit — all nullable; NULL = live/canonical report.
ALTER TABLE task ADD COLUMN IF NOT EXISTS merged_into_post_id BIGINT;
ALTER TABLE task ADD COLUMN IF NOT EXISTS merged_at           TIMESTAMP;
ALTER TABLE task ADD COLUMN IF NOT EXISTS merged_by_email     VARCHAR(320);

-- 2. FK -> task(id) ON DELETE SET NULL (survivor deletion detaches duplicates).
--    Guarded so a re-run (e.g. after a partially-applied attempt) is a no-op;
--    Postgres has no ADD CONSTRAINT IF NOT EXISTS.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_task_merged_into') THEN
        ALTER TABLE task
            ADD CONSTRAINT fk_task_merged_into
            FOREIGN KEY (merged_into_post_id) REFERENCES task (id) ON DELETE SET NULL;
    END IF;
END $$;

-- 3. No self-merge (cycle-proof floor; chains flattened in the service).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_task_merged_not_self') THEN
        ALTER TABLE task
            ADD CONSTRAINT ck_task_merged_not_self
            CHECK (merged_into_post_id IS NULL OR merged_into_post_id <> id);
    END IF;
END $$;

-- 4. Read path: canonical -> its duplicates (mergedDuplicateCount/ids fold) and
--    the queue filter (WHERE merged_into_post_id IS NULL). Plain transactional
--    CREATE INDEX — NOT CONCURRENTLY.
CREATE INDEX IF NOT EXISTS idx_task_merged_into ON task (merged_into_post_id);
