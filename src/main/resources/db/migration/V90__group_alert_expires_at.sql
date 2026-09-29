-- A check-in's end, separate from its start (ask-to-check-in plan K1/K4,
-- docs/epics/map_and_slate/CHECKIN-CONSISTENCY-AUDIT-2026-09-29.md).
--
-- The auto-end used to be alert_activated_at + 48h, so the only way to keep a
-- check-in going was to start it again, which re-asked everyone and made every
-- answer read as "No response" (answers older than the start don't count).
-- "Continue" now pushes this back and leaves alert_activated_at — and so every
-- answer — alone. NULL = the old rule (start + 48h), for check-ins already
-- running at deploy.
ALTER TABLE groups ADD COLUMN IF NOT EXISTS alert_expires_at TIMESTAMP;
