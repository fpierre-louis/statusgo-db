-- V104 — Practice scenario runs (scenarios & Family Practice epic, EXEC-B
-- adult scenario MVP, 2026-10-08).
--
-- ONE table for in-progress, completed and abandoned runs; history and
-- progress are derived from it (no scenario_completion table, no score).
-- Each run pins the exact content it was started on — scenario_key +
-- content_version + content_hash — so publishing a new version or retiring
-- this one never changes what an existing run shows.
--
-- decision_trace holds node/choice KEYS only (never free text):
--   [{"nodeKey":"start","choiceKey":"text","at":"2026-10-08T15:00:00Z"}, ...]
-- debrief_tags holds DebriefTag wire names: ["communicated_clearly", ...]
--
-- One IN_PROGRESS run per (household, scenario), and per (user, scenario)
-- when the run has no household: start-or-resume, so a double tap or a second
-- device resumes instead of forking. Partial indexes, so JPA cannot declare
-- them and H2 cannot build them (SYSTEM_TRAPS T-2); ScenarioService also
-- resolves the race on insert.
--
-- Plain transactional DDL (no CONCURRENTLY — prod RDS times out).

CREATE TABLE IF NOT EXISTS scenario_run (
    id                   BIGSERIAL    PRIMARY KEY,
    household_id         VARCHAR(255) NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    user_email           VARCHAR(320) NOT NULL,
    scenario_key         VARCHAR(96)  NOT NULL,
    content_version      INTEGER      NOT NULL,
    content_hash         VARCHAR(128) NOT NULL,
    status               VARCHAR(24)  NOT NULL,
    started_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ  NULL,
    abandoned_at         TIMESTAMPTZ  NULL,
    last_node_key        VARCHAR(96)  NULL,
    outcome_key          VARCHAR(96)  NULL,
    decision_trace       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    debrief_tags         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    suggested_action_key VARCHAR(96)  NULL,
    row_version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_scenario_run_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'ABANDONED'))
);

CREATE INDEX IF NOT EXISTS idx_scenario_run_household_started
    ON scenario_run (household_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_scenario_run_user_started
    ON scenario_run (user_email, started_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uk_scenario_run_household_active
    ON scenario_run (household_id, scenario_key)
    WHERE status = 'IN_PROGRESS' AND household_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_scenario_run_user_active
    ON scenario_run (user_email, scenario_key)
    WHERE status = 'IN_PROGRESS' AND household_id IS NULL;
