ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS challenge_last_shown_week VARCHAR(16);

CREATE TABLE IF NOT EXISTS group_advanced_readiness_progress (
    group_id      VARCHAR(255) NOT NULL,
    item_key      VARCHAR(96)  NOT NULL,
    completed_at  TIMESTAMPTZ  NOT NULL,
    completed_by  VARCHAR(320),
    PRIMARY KEY (group_id, item_key)
);

CREATE INDEX IF NOT EXISTS group_advanced_readiness_progress_group_ix
    ON group_advanced_readiness_progress (group_id);
