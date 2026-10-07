-- V98 — readiness item state (Ready for More / readiness foundation epic, 2026-10-07).
--
-- Contract: SitPrep FE docs/epics/ready_for_more/CONTRACT.md §8.
--
-- The backend owns readiness. Completion for most steps comes from the real
-- domain (contacts, stockpile, evacuation, drill log, plan confirmation).
-- This table holds only what the domain cannot know:
--   * HOUSEHOLD rows: DONE (self-reported MANUAL steps) and NOT_RELEVANT
--     (an admin says the step does not apply) — one row per household + item.
--   * USER rows: SKIPPED (suppressed_until) and REMIND_LATER (remind_at) —
--     one row per household + item + user. They affect ranking only.
-- item_key carries no CHECK: the catalog lives in Java (ReadinessCatalog),
-- same reasoning as V94's token_key.
--
-- The one-row-per-scope rule is two PARTIAL unique indexes. H2 cannot express
-- them (SYSTEM_TRAPS T-2); the service enforces it with find-then-update in
-- one transaction, and these indexes were verified on a scratch local
-- Postgres (EXEC-B1.md).
--
-- Legacy: group_advanced_readiness_progress (V66) was a free-form admin
-- self-report map. Only `documentVault` maps onto a MANUAL step
-- (documents.first_folder); every other legacy key described a fact the real
-- domain now owns (contacts, stockpile, drills), so it is intentionally not
-- carried. The table is dropped in the same release that removes its
-- @ElementCollection mapping (ddl-auto=validate).
--
-- Plain transactional DDL (no CONCURRENTLY — prod RDS times out).

CREATE TABLE IF NOT EXISTS household_readiness_item_state (
    id               BIGSERIAL    PRIMARY KEY,
    household_id     VARCHAR(255) NOT NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    item_key         VARCHAR(96)  NOT NULL,
    scope            VARCHAR(16)  NOT NULL,
    user_email       VARCHAR(320),
    state            VARCHAR(32)  NOT NULL,
    suppressed_until TIMESTAMPTZ,
    remind_at        TIMESTAMPTZ,
    reason_code      VARCHAR(64),
    created_by       VARCHAR(320) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_hris_scope CHECK (scope IN ('HOUSEHOLD', 'USER')),
    CONSTRAINT ck_hris_state CHECK (state IN ('DONE', 'NOT_RELEVANT', 'SKIPPED', 'REMIND_LATER')),
    CONSTRAINT ck_hris_email_lower CHECK (user_email IS NULL OR user_email = lower(user_email)),
    CONSTRAINT ck_hris_scope_email CHECK (
        (scope = 'HOUSEHOLD' AND user_email IS NULL)
        OR (scope = 'USER' AND user_email IS NOT NULL)),
    CONSTRAINT ck_hris_state_scope CHECK (
        (state IN ('DONE', 'NOT_RELEVANT') AND scope = 'HOUSEHOLD')
        OR (state IN ('SKIPPED', 'REMIND_LATER') AND scope = 'USER')),
    CONSTRAINT ck_hris_skipped_until CHECK (state <> 'SKIPPED' OR suppressed_until IS NOT NULL),
    CONSTRAINT ck_hris_remind_at CHECK (state <> 'REMIND_LATER' OR remind_at IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_hris_household_item
    ON household_readiness_item_state (household_id, item_key)
    WHERE scope = 'HOUSEHOLD';

CREATE UNIQUE INDEX IF NOT EXISTS uk_hris_user_item
    ON household_readiness_item_state (household_id, item_key, user_email)
    WHERE scope = 'USER';

CREATE INDEX IF NOT EXISTS idx_hris_household
    ON household_readiness_item_state (household_id);

-- Legacy carry-over: documentVault → documents.first_folder DONE.
-- The JOIN on groups skips orphaned legacy rows (V66 had no FK), which would
-- otherwise fail the new FK and abort the migration.
DO $$
BEGIN
    IF to_regclass('group_advanced_readiness_progress') IS NOT NULL THEN
        INSERT INTO household_readiness_item_state
            (household_id, item_key, scope, user_email, state,
             created_by, created_at, updated_at)
        SELECT l.group_id, 'documents.first_folder', 'HOUSEHOLD', NULL, 'DONE',
               COALESCE(NULLIF(lower(trim(l.completed_by)), ''), 'legacy'),
               l.completed_at, l.completed_at
          FROM group_advanced_readiness_progress l
          JOIN groups g ON g.group_id = l.group_id
         WHERE l.item_key = 'documentVault'
        ON CONFLICT (household_id, item_key) WHERE scope = 'HOUSEHOLD' DO NOTHING;
    END IF;
END $$;

DROP TABLE IF EXISTS group_advanced_readiness_progress;
