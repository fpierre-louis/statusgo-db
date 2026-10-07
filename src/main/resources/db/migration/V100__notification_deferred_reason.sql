-- V100 — mark the inbox rows quiet hours held (EXEC-N notification follow-ups,
-- 2026-10-07).
--
-- A Lane A push that lands inside the recipient's own quiet window is written
-- as a Lane B inbox row instead. Until now nothing recorded WHY a row was
-- Lane B, so nothing could tell "held overnight" from "rate-capped", "push
-- switched off" or "always inbox-only". QuietHoursCatchUpService sends ONE
-- "N updates while your notifications were quiet" push when the window ends,
-- counting only unread rows marked here.
--
-- Values: 'QUIET_HOURS' (PushPolicyService.DeferReason). Null on every other
-- row, and on every row written before this migration (no backfill: a past
-- night has already ended).
--
-- Plain transactional DDL (no CONCURRENTLY — prod RDS times out). The column
-- add is metadata-only on PostgreSQL 11+; the partial index starts empty.

ALTER TABLE notification_log
    ADD COLUMN IF NOT EXISTS deferred_reason VARCHAR(16);

-- The catch-up sweep's predicate: marked rows by recipient, newest window.
CREATE INDEX IF NOT EXISTS idx_notif_deferred_recipient_ts
    ON notification_log (recipient_email, timestamp)
    WHERE deferred_reason IS NOT NULL;
