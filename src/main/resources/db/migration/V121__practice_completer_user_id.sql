-- V121 — Practice completer identity moves off raw email (owner ruling 2026-10-22).
--
-- New Practice writes store the caller's stable UserInfo.id and stop writing the
-- email: family completions and drill-log rows get a nullable completed_by_user_id,
-- scenario_run gets a nullable user_id. Historical rows are NOT rewritten or
-- backfilled; their email columns keep whatever they hold.
--
-- family_practice_completion.completed_by_email was NOT NULL (V113), so it is
-- relaxed here; new rows leave it null. scenario_run.user_email stays NOT NULL on
-- purpose: it is the operational owner of a personal run (the partial unique index
-- uk_scenario_run_user_active is keyed on it), so only the id is added beside it.
--
-- No foreign keys: a lookup miss must still let the write through (id stays null),
-- and a deleted user must not block or cascade into shared household history.

ALTER TABLE family_practice_completion
    ADD COLUMN IF NOT EXISTS completed_by_user_id VARCHAR(255);

ALTER TABLE family_practice_completion
    ALTER COLUMN completed_by_email DROP NOT NULL;

ALTER TABLE group_drill_log
    ADD COLUMN IF NOT EXISTS completed_by_user_id VARCHAR(255);

ALTER TABLE scenario_run
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(255);
