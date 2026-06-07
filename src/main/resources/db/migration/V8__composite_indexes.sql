-- Composite + functional indexes for hot filters — audit P3-6
--
-- Targets three hot read paths that currently fall back to seq scan or
-- single-column index lookups:
--   1. Personal task feed (group_id IS NULL) filtered by requester + kind —
--      community feed pulls per-user asks/offers/announcements without a
--      group_id. The existing idx_task_requester is single-column and the
--      partial index here adds kind selectivity for the NULL-group path.
--   2. Household event timeline by actor (profile activity / audit views) —
--      V1 already indexes (household_id, at) but actor-scoped queries (e.g.
--      "what did this user do") scan the table.
--   3. Notification recipient lookups using LOWER(email) — V4 added
--      lower-email indexes for plan tables; notification_log was missed and
--      every IgnoreCase lookup against recipient_email seq-scans.

CREATE INDEX IF NOT EXISTS idx_task_requester_kind
    ON task (LOWER(requester_email), kind)
    WHERE group_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_household_event_actor_at
    ON household_event (actor_email, at DESC);

CREATE INDEX IF NOT EXISTS idx_notification_log_recipient_lower
    ON notification_log (LOWER(recipient_email));
