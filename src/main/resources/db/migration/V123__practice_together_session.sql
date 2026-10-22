-- V123 — Together sessions (Practice redesign FR-8 / BE-09).
--
-- One row = "this household is partway through this Together activity, at this turn, in this
-- style". Nothing else: no child, no age, no role holder, no actor (CHILD_SAFETY_REVIEW).
-- The row ends at expires_at (started_at + 24 h; swept daily), when the household completes
-- the activity, when a new session for the same activity replaces it, with "Clear practice
-- history", and with the household (FK cascade).

CREATE TABLE IF NOT EXISTS practice_together_session (
    id              BIGSERIAL    PRIMARY KEY,
    household_id    VARCHAR(255) NOT NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    activity_key    VARCHAR(96)  NOT NULL,
    content_version INTEGER      NOT NULL,
    style           VARCHAR(24)  NOT NULL,
    turn_index      INTEGER      NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_practice_together_session_household_activity
    ON practice_together_session (household_id, activity_key);

CREATE INDEX IF NOT EXISTS idx_practice_together_session_expires_at
    ON practice_together_session (expires_at);
