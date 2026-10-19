-- V113 — Family Practice household completions (Phase D, 2026-10-19).
--
-- Family activities are adult-facilitated and store one household-level fact:
-- this version of this activity was completed. Sorting choices stay in the
-- browser and are never sent. This intentionally does not reuse
-- group_drill_log: family content has immutable versions/hashes and must not
-- enter drill cadence or DRILL_COMPLETED token logic.

CREATE TABLE IF NOT EXISTS family_practice_completion (
    id                 BIGSERIAL    PRIMARY KEY,
    household_id       VARCHAR(255) NOT NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    activity_key       VARCHAR(96)  NOT NULL,
    content_version    INTEGER      NOT NULL,
    content_hash       VARCHAR(128) NOT NULL,
    completed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_by_email VARCHAR(320) NOT NULL,
    debrief_tags       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    row_version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_family_practice_completion_version
        UNIQUE (household_id, activity_key, content_version)
);

CREATE INDEX IF NOT EXISTS idx_family_practice_completion_household_completed
    ON family_practice_completion (household_id, completed_at DESC);
