-- V99 — REMIND_LATER becomes a real reminder (Ready for More audit EXEC-A1,
-- 2026-10-07).
--
-- Contract: SitPrep FE docs/epics/ready_for_more/CONTRACT.md §6 (amended).
--
-- Until now "Remind me later" only hid the step from ranking until remind_at;
-- nothing reminded anyone. ReadinessReminderService now sweeps due
-- REMIND_LATER rows and sends ONE notification per row. reminded_at records
-- that the row's reminder was handled (sent, or closed because the step no
-- longer needs it) so it is never sent twice; the sweep stamps it with a
-- conditional UPDATE … WHERE reminded_at IS NULL, so two instances racing on
-- the same row send once.
--
-- Re-snoozing writes a new remind_at and clears reminded_at (service), and a
-- non-REMIND_LATER row never carries it (CHECK below).
--
-- Plain transactional DDL (no CONCURRENTLY — prod RDS times out). The table
-- is tiny (0 rows in prod at authoring time).

ALTER TABLE household_readiness_item_state
    ADD COLUMN IF NOT EXISTS reminded_at TIMESTAMPTZ;

ALTER TABLE household_readiness_item_state
    ADD CONSTRAINT ck_hris_reminded_state CHECK (reminded_at IS NULL OR state = 'REMIND_LATER');

-- The sweep's predicate: due, unsent REMIND_LATER rows.
CREATE INDEX IF NOT EXISTS idx_hris_remind_due
    ON household_readiness_item_state (remind_at)
    WHERE state = 'REMIND_LATER' AND reminded_at IS NULL;
