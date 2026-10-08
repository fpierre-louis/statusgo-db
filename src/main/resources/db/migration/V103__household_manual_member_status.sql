-- V103 — a status for a manual household member (household drawer gameplan
-- §5.2, owner ruling Q5, 2026-10-08).
--
-- A manual member (a child, an elder without a phone) cannot answer a
-- check-in, so an owner or admin answers for them: SAFE | HELP | INJURED.
-- Mirrors V72's proxy columns on user_info:
--
--   status              SAFE | HELP | INJURED, null when unset or cleared
--   status_updated_at   when it was set — SAFE shows for 24h from here, or
--                       from the check-in's start while one runs
--   status_set_by_email the admin who set it; the roster names them
--
-- No colour column: the colour is derived on the server, as for accounts.
-- No audit table: each write records a status-set-for household_event.
-- A claim drops all three (HouseholdClaimService.migrateReferences) — a proxy
-- SAFE must never become the claiming account's self-report.
--
-- V103, not V102: V102 is taken by the practice content lane's
-- V102__practice_content_control.sql. There is no outOfOrder, so V102 must
-- reach prod before or with this one.
--
-- Plain transactional DDL (no CONCURRENTLY — prod RDS times out). Small table.

ALTER TABLE household_manual_member
    ADD COLUMN IF NOT EXISTS status              VARCHAR(16),
    ADD COLUMN IF NOT EXISTS status_updated_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS status_set_by_email VARCHAR(255);
