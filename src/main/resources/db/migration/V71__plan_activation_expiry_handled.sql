-- V71 - The 72-hour timer gets a memory.
--
-- The expiry is the one path that ends an activation by CEASING TO MATCH A
-- QUERY. Nothing was broadcast, so every client kept reading EVACUATING until
-- it happened to refetch /me. A scheduled tick can broadcast instead — but a
-- tick with no memory re-broadcasts the same row every hour until the delete
-- sweep removes it 14 days later, which would put one "the plan ended" row per
-- hour into the household's history.
--
-- WHY THIS IS NOT `ended_at`.
--
-- `ended_at` means A PERSON SAID IT WAS OVER. That is not a nicety; four
-- places depend on it, and the frontend is the loudest:
-- EndActivationControl renders "Your household ended this" off this exact
-- field, and activationEnd.test.js has a case named "EXPIRED is closed with
-- NO endedAt — nobody said it was over". Stamping the timer into `ended_at`
-- would make the app claim a household declared something a clock did.
--
-- So this column claims only what the sweep itself did: it handled the row.
--
-- BACKFILL. Rows that expired BEFORE this migration are marked handled without
-- ever being announced, deliberately. A lifecycle frame is a convergence
-- signal; there is nothing left to converge about an activation that expired
-- days ago, and recording it now would date a days-old ending to today in the
-- household timeline. 8 of the 9 rows in production are in this state.

ALTER TABLE plan_activations
    ADD COLUMN IF NOT EXISTS expiry_handled_at timestamptz;

UPDATE plan_activations
   SET expiry_handled_at = expires_at
 WHERE expiry_handled_at IS NULL
   AND expires_at <= now();

-- The sweep's candidate scan is (expires_at, ended_at, expiry_handled_at).
-- Partial index: the rows it looks for are the ones not yet handled, which is
-- a handful at any moment even when the table is large.
CREATE INDEX IF NOT EXISTS idx_plan_activations_expiry_unhandled
    ON plan_activations (expires_at)
 WHERE expiry_handled_at IS NULL;
