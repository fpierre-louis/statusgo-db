-- V94 — Readiness Tokens ledger (docs/epics/readiness_tokens/EXEC-T1).
--
-- A token is a credential the server can prove: "this household has a meeting
-- place", "this person answered a neighbour's question". One row per
-- (subject, token) — the unique constraints ARE the idempotency guarantee.
-- Awards are written with INSERT … ON CONFLICT DO NOTHING, so evaluating the
-- same subject a hundred times inserts once, and only that insert notifies.
--
-- Two ledgers because the subjects differ: a person (lower-cased email, the
-- convention of post_confirm / hazard_vote / map_confirmation) and a household
-- (groups.group_id, a VARCHAR — not a bigint). Household awards are seen per
-- member, so their seen state lives in its own table rather than one column.
--
-- token_key carries no CHECK: the catalog ships in Java, and a CHECK would turn
-- every new token into a migration. No backfill (owner, 2026-10-03): everyone
-- starts from zero.
--
-- Plain transactional DDL; new, empty tables. Postgres-only; never runs under
-- the H2 test profile.

CREATE TABLE IF NOT EXISTS user_token_ledger (
    id                 BIGSERIAL    PRIMARY KEY,
    user_email         VARCHAR(320) NOT NULL,
    token_key          VARCHAR(64)  NOT NULL,
    earned_at          TIMESTAMP    NOT NULL,
    seen_at            TIMESTAMP    NULL,
    source_event_type  VARCHAR(48)  NULL,
    source_event_id    VARCHAR(128) NULL,
    metadata           JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_token_ledger_user_token
    ON user_token_ledger (user_email, token_key);

-- The read: a person's tokens, newest first.
CREATE INDEX IF NOT EXISTS idx_user_token_ledger_user_earned
    ON user_token_ledger (user_email, earned_at DESC);

CREATE TABLE IF NOT EXISTS household_token_ledger (
    id                 BIGSERIAL    PRIMARY KEY,
    household_id       VARCHAR(255) NOT NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    token_key          VARCHAR(64)  NOT NULL,
    earned_at          TIMESTAMP    NOT NULL,
    earned_by_email    VARCHAR(320) NULL,
    source_event_type  VARCHAR(48)  NULL,
    source_event_id    VARCHAR(128) NULL,
    metadata           JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_household_token_ledger_household_token
    ON household_token_ledger (household_id, token_key);

CREATE INDEX IF NOT EXISTS idx_household_token_ledger_household_earned
    ON household_token_ledger (household_id, earned_at DESC);

-- Which members have seen a household award (the unlock toast fires once per
-- member, not once per household).
CREATE TABLE IF NOT EXISTS household_token_seen (
    award_id    BIGINT       NOT NULL REFERENCES household_token_ledger (id) ON DELETE CASCADE,
    user_email  VARCHAR(320) NOT NULL,
    seen_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (award_id, user_email)
);
