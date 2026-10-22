-- V122 — Spaced recall for Practice (redesign FR-7 / BE-05, BE-06).
--
-- practice_skill_state: one row per (household, skill). Only a check-in answer
-- writes it; a skill nobody has answered has no row, and its first check-in is due
-- from the household's first exposure (computed on read, never stored).
-- interval_days is the interval chosen by the newest answer; prior_interval_days is
-- the interval it was chosen FROM, so a confidence tap can re-schedule that same
-- answer without replaying history (the 12-month sweep deletes old answers, never
-- this state).
--
-- practice_checkin_answer: the household-level fact "this check-in version was
-- answered, correct or not". No actor column on purpose (owner ruling 2026-10-22:
-- Practice facts are the household's, not a person's). `correct` is internal; it
-- steers scheduling and is never sent to a client.
--
-- Both rows go with the household (FK cascade), with "Clear practice history", and
-- answers go after 12 months (PracticeRetentionSweepService); state is kept.

CREATE TABLE IF NOT EXISTS practice_skill_state (
    id                  BIGSERIAL    PRIMARY KEY,
    household_id        VARCHAR(255) NOT NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    skill_key           VARCHAR(96)  NOT NULL,
    last_seen_at        TIMESTAMPTZ  NOT NULL,
    interval_days       INTEGER      NOT NULL,
    prior_interval_days INTEGER      NOT NULL,
    next_due_at         TIMESTAMPTZ  NOT NULL,
    last_result         VARCHAR(16)  NOT NULL,
    last_confidence     VARCHAR(16),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    row_version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_practice_skill_state_household_skill UNIQUE (household_id, skill_key)
);

CREATE INDEX IF NOT EXISTS idx_practice_skill_state_household_due
    ON practice_skill_state (household_id, next_due_at);

CREATE TABLE IF NOT EXISTS practice_checkin_answer (
    id              BIGSERIAL    PRIMARY KEY,
    household_id    VARCHAR(255) NOT NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    skill_key       VARCHAR(96)  NOT NULL,
    checkin_key     VARCHAR(96)  NOT NULL,
    content_version INTEGER      NOT NULL,
    correct         BOOLEAN      NOT NULL,
    confidence      VARCHAR(16),
    answered_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    row_version     BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_practice_checkin_answer_household_skill
    ON practice_checkin_answer (household_id, skill_key, answered_at DESC);

CREATE INDEX IF NOT EXISTS idx_practice_checkin_answer_answered_at
    ON practice_checkin_answer (answered_at);
