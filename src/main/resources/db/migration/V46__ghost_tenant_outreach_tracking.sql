-- V46 — Ghost Tenant outreach tracking (Phase 3 of the consent-first claim model).
--
-- Adds the state a privacy-safe outreach worker needs to email the ONE
-- human-verified official channel of an unclaimed ("GHOST") civic group, at a
-- strict weekly cadence, a lifetime cap, and a permanent one-click opt-out.
-- There is NO scraping and NO social-media targeting anywhere in this feature —
-- outreach is only ever sent to official_contact_email, which a human sets.
--
-- Depends on V45 (unified_workorder_schema), which adds groups.claim_state and
-- groups.ghost_demand_signal. Additive + idempotent (ADD COLUMN / CREATE TABLE
-- IF NOT EXISTS); plain transactional DDL on a small table (no CONCURRENTLY).
-- Matching Group / GhostDemandVote entity fields ship with this migration so
-- ddl-auto=validate stays satisfied. Instant fields map to TIMESTAMPTZ.

-- --- Outreach state on the ghost group ---------------------------------------
ALTER TABLE groups ADD COLUMN IF NOT EXISTS official_contact_email VARCHAR(320);
ALTER TABLE groups ADD COLUMN IF NOT EXISTS outreach_opt_out       BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE groups ADD COLUMN IF NOT EXISTS last_outreach_date     TIMESTAMPTZ;
ALTER TABLE groups ADD COLUMN IF NOT EXISTS outreach_count         INTEGER NOT NULL DEFAULT 0;

-- --- Distinct-resident demand ledger -----------------------------------------
-- Makes ghost_demand_signal an idempotent count of DISTINCT residents (one +1
-- per resident), not a spammable counter — so a single person can't inflate the
-- signal and trigger unwanted outreach. Aggregate/behavioural only; no PII
-- beyond the voter's own account email (used solely for the unique-per-resident
-- constraint).
CREATE TABLE IF NOT EXISTS ghost_demand_vote (
    id          BIGSERIAL    PRIMARY KEY,
    group_id    VARCHAR(255) NOT NULL,
    voter_email VARCHAR(320) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_ghost_demand_vote UNIQUE (group_id, voter_email)
);
CREATE INDEX IF NOT EXISTS idx_ghost_demand_vote_group ON ghost_demand_vote (group_id);
