-- Per-drill, dated completion for household preparedness drills.
--
-- WHAT IT REPLACES. `group_challenge_progress` maps an ISO WEEK to a boolean,
-- so the app has never known WHICH drill a household did — only that one was
-- done that week. "When did we last do this drill" has been unanswerable, and
-- the "Drills done · N" count on the dashboard has always counted weeks: two
-- drills in one week recorded as one.
--
-- THE OLD TABLE IS NOT TOUCHED AND NOT BACKFILLED. It cannot be: a week key
-- carries no drill id, so there is nothing to migrate a row INTO. Inventing one
-- would fabricate a record of a drill nobody can show was done. The week map
-- stays readable until the frontend stops asking for it, and is dropped then.
--
-- drill_key is a catalog id, optionally with a phase: "go-bag" or
-- "go-bag#papers". A split drill records each part's own date, so a household
-- can pack the documents on a different evening from the water.
--
-- Shape follows `group_advanced_readiness_progress` exactly — an
-- @ElementCollection map side-table with completed_at / completed_by. Boring,
-- indexable, no JSON column, already proven in this schema.

CREATE TABLE IF NOT EXISTS group_drill_log (
    group_id     VARCHAR(255) NOT NULL,
    drill_key    VARCHAR(96)  NOT NULL,
    completed_at TIMESTAMP    NOT NULL,
    completed_by VARCHAR(320),
    PRIMARY KEY (group_id, drill_key)
);

-- The only query shape is "every drill this household has done", which the
-- composite primary key's leading column already serves. No second index:
-- one that is never used is a write cost with a maintenance story.
