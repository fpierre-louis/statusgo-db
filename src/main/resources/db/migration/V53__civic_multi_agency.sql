-- V53 — Civic epic Slice 2: multi-agency tagging + claim/release.
--
-- Turns a civic report's SINGLE tagged agency (task.tagged_agency_group_id) into
-- MANY, via a join table, and adds the claim/release lifecycle + the orphan
-- coverage-gap ledger. Backfill is INSERT-ONLY (it reads `task`, writes only the
-- new join table — it never UPDATEs or DELETEs an existing row).
--
-- ORDERING (mirrors V50's precedent — the partial-unique index goes LAST, after
-- the backfill, so it validates an already-consistent state):
--   1. civic_report_agency table (the many-to-many + per-tag claim state)
--   2. its two btree indexes
--   3. task.claiming_agency_group_id — the denormalized single-claim mirror
--      (the assignee_email-style hot-path mirror; dual-written by the service)
--   4. civic_coverage_gap — the zip-keyed orphan ledger (decision 2)
--   5. BACKFILL: one legacy join row per existing tagged civic report (INSERT ONLY)
--   6. uk_civic_report_one_claim — partial-unique "one active claim per report",
--      added LAST (post-backfill; all backfilled rows are claimed=false so it
--      validates trivially). Postgres-only partial index (not a JPA annotation);
--      the H2 test profile builds the table from the entity via
--      ddl-auto=create-drop and does not exercise this index or the FK — same
--      arrangement as V48/V50.
--
-- SAFETY: plain transactional DDL on small/new tables — NO CREATE INDEX
-- CONCURRENTLY (dodges the prod statement_timeout trap). taggedAgencyGroupId is
-- KEPT (decision 7) as the dual-written first/primary-tag display mirror; a later
-- cleanup migration retires it once all readers move to the join.
--
-- ROLLBACK-IN-THINKING: reversing V53 = drop uk_civic_report_one_claim, drop
-- civic_coverage_gap, drop task.claiming_agency_group_id, drop civic_report_agency
-- (its rows are derived from task.tagged_agency_group_id, which is untouched).

-- 1. The many-to-many tag rows + per-tag claim state.
CREATE TABLE IF NOT EXISTS civic_report_agency (
    id               BIGSERIAL PRIMARY KEY,
    post_id          BIGINT       NOT NULL REFERENCES task (id) ON DELETE CASCADE,
    agency_group_id  VARCHAR(64)  NOT NULL,
    tag_source       VARCHAR(16)  NOT NULL,   -- auto | citizen_added | legacy
    active           BOOLEAN      NOT NULL DEFAULT true,   -- false = citizen-deselected tombstone
    claimed          BOOLEAN      NOT NULL DEFAULT false,
    claimed_at       TIMESTAMP,
    claimed_by_email VARCHAR(320),
    released_at      TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL
);

-- 2. Read paths: fold a report's tags (post_id); the agency queue (agency_group_id, claimed).
CREATE INDEX IF NOT EXISTS idx_cra_post           ON civic_report_agency (post_id);
CREATE INDEX IF NOT EXISTS idx_cra_agency_claimed ON civic_report_agency (agency_group_id, claimed);
-- One active tag row per (report, agency) — keeps re-tagging idempotent; a
-- citizen-deselected tombstone (active=false) coexists with no active row.
CREATE UNIQUE INDEX IF NOT EXISTS uk_cra_post_agency ON civic_report_agency (post_id, agency_group_id);

-- 3. Denormalized single-claim mirror on the report (nullable; null = unclaimed).
ALTER TABLE task ADD COLUMN IF NOT EXISTS claiming_agency_group_id VARCHAR(64);

-- 4. Zip-keyed orphan ledger — a civic report whose location has NO covering
--    authorized agency becomes a coverage-gap demand signal (decision 2). Keyed
--    by zip; the ghost/onboarding pipeline reads it to prioritize recruitment.
CREATE TABLE IF NOT EXISTS civic_coverage_gap (
    id            BIGSERIAL PRIMARY KEY,
    zip           VARCHAR(12)  NOT NULL,
    last_category VARCHAR(24),                 -- most-recent civic category (prioritization hint)
    report_count  INTEGER      NOT NULL DEFAULT 0,
    first_seen    TIMESTAMP    NOT NULL,
    last_seen     TIMESTAMP    NOT NULL,
    CONSTRAINT uk_civic_coverage_gap_zip UNIQUE (zip)
);

-- 5. BACKFILL — INSERT ONLY. One legacy join row per existing civic report that
--    carries a single tagged agency. Reads `task`; writes only civic_report_agency.
--    Does NOT touch task.tagged_agency_group_id (kept as the mirror, decision 7).
INSERT INTO civic_report_agency (post_id, agency_group_id, tag_source, active, claimed, created_at)
SELECT t.id, t.tagged_agency_group_id, 'legacy', true, false, COALESCE(t.created_at, now())
FROM task t
WHERE t.kind = 'civic-report'
  AND t.tagged_agency_group_id IS NOT NULL
  AND t.tagged_agency_group_id <> '';

-- 6. One active claim per report — partial-unique, added LAST (V50 precedent).
CREATE UNIQUE INDEX IF NOT EXISTS uk_civic_report_one_claim
    ON civic_report_agency (post_id)
    WHERE claimed = true;
