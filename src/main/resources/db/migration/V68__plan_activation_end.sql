-- Give a plan activation an end.
--
-- Until now the only thing that stopped an activation was its 72-hour
-- `expires_at`. There was no endpoint to close one and no column to record a
-- close, so "we are done evacuating" was a sentence a household could not say —
-- and Home kept reading EVACUATING from a three-day-old row while every other
-- surface said all quiet. The most recent prod row (activated 2026-08-29 17:31,
-- expired 2026-09-01 17:31) is that defect: 72 hours of evacuation, ended by a
-- timer.
--
-- BOTH COLUMNS ARE NULLABLE AND THERE IS NO BACKFILL. Every existing row is
-- already past `expires_at`, so both active queries exclude it before the new
-- `ended_at IS NULL` clause is ever reached — this migration changes what zero
-- rows resolve to today. On Postgres an ADD COLUMN with no default is a
-- catalog-only change; nothing is rewritten.
--
-- NO INDEX, deliberately. `ended_at` is only ever read alongside `owner_email`
-- and `expires_at` in queries that are already owner-scoped, and the table is
-- eight rows. An index here would be a guess about a shape we have not measured.

ALTER TABLE plan_activations
    ADD COLUMN IF NOT EXISTS ended_at       TIMESTAMP,
    ADD COLUMN IF NOT EXISTS ended_by_email VARCHAR(255);
