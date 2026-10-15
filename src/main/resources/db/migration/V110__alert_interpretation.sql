-- V110 — Alert interpretation persistence (Alerts & Warnings Phase 3, S2 unit C3,
-- 2026-10-14). Contract: docs/epics/alerts/alerts_and_warnings/EXEC-phase3-S2-contract.md §6.
--
-- Reserved for this lane: the group lane's V108/V109 reached prod first and
-- Flyway out-of-order stays off, so the contract's original V107 became V110.
--
--   alert_source_revision          one row per upstream revision (dedupeKey@fp12),
--                                  payload stored ONCE; last_seen_at moves only
--                                  for a source that answered the poll
--   alert_interpretation_snapshot  one row per (revision, policy, interpreter,
--                                  template, decision, lifecycle mode) — what
--                                  SitPrep said about that revision, with no
--                                  viewer context and no engagement counts. The
--                                  lifecycle mode (record or live + lifecycle
--                                  verdict) keeps a record from ever sharing the
--                                  live row's current=true and primary action
--   notification_log.alert_*       which alert/revision/thread/snapshot a row
--                                  was about (written by C5's dispatch)
--   alert_post.resolved_reason     why a dispatched post was resolved (C6)
--
-- Written by InterpretationRecorder after each successful ingest tick. Swept by
-- RetentionSweepService in the order notification_log -> unreferenced
-- snapshots -> unreferenced revisions (30 days); the snapshot FK below is
-- ON DELETE RESTRICT so a revision can never be swept out from under one.
--
-- One addition beyond §6: idx_nl_snapshot, a PARTIAL index on
-- notification_log(interpretation_snapshot_id). The snapshot sweep asks "is any
-- notification row pointing at this snapshot?" for every candidate; without it
-- that is a full scan of the biggest log table per batch. Every existing row is
-- null, so the index builds from nothing.
--
-- Plain transactional DDL (no CONCURRENTLY — prod RDS times out).

CREATE TABLE IF NOT EXISTS alert_source_revision (
    revision_ref      VARCHAR(320) PRIMARY KEY,      -- dedupeKey@fp12
    dedupe_key        VARCHAR(300) NOT NULL,
    source            VARCHAR(16)  NOT NULL,
    upstream_id       VARCHAR(280) NOT NULL,
    fingerprint       CHAR(64)     NOT NULL,
    vtec_event_keys   TEXT,                          -- space-separated, for batched thread lookups
    reference_ids     TEXT,                          -- space-separated CAP identifiers
    sent_at           TIMESTAMPTZ,
    payload           TEXT         NOT NULL,         -- normalized alert JSON, ONCE per revision
    first_seen_at     TIMESTAMPTZ  NOT NULL,
    last_seen_at      TIMESTAMPTZ  NOT NULL          -- retention-grade: moved at most hourly, never a liveness bound
);
CREATE INDEX IF NOT EXISTS idx_asr_dedupe ON alert_source_revision (dedupe_key);
CREATE INDEX IF NOT EXISTS idx_asr_last_seen ON alert_source_revision (last_seen_at);

CREATE TABLE IF NOT EXISTS alert_interpretation_snapshot (
    id                      UUID PRIMARY KEY,
    fingerprint             CHAR(64)     NOT NULL UNIQUE,  -- revision_ref + policy_version + interpreter_version + template_key + template_review_version + decision fields + lifecycle mode
    revision_ref            VARCHAR(320) NOT NULL REFERENCES alert_source_revision (revision_ref) ON DELETE RESTRICT,
    dedupe_key              VARCHAR(300) NOT NULL,
    policy_version          VARCHAR(40)  NOT NULL,
    interpreter_version     VARCHAR(40)  NOT NULL,
    template_key            VARCHAR(200),
    template_review_version INT,
    decision_json           TEXT         NOT NULL,         -- dispatchMode/guidanceMode/compatibility/capActions/movementDirective/asIssued/reason
    content_json            TEXT         NOT NULL,         -- interpreter + presentation + sourceIdentity + lifecycleInfo at creation (NO viewerContext, NO engagement counts)
    created_at              TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ais_revision ON alert_interpretation_snapshot (revision_ref);

ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS alert_key VARCHAR(300);
ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS alert_revision_ref VARCHAR(320);
ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS alert_thread_key VARCHAR(200);
ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS interpretation_snapshot_id UUID;
CREATE INDEX IF NOT EXISTS idx_nl_recipient_thread ON notification_log (recipient_email, alert_thread_key);
CREATE INDEX IF NOT EXISTS idx_nl_recipient_alert ON notification_log (recipient_email, alert_key);
CREATE INDEX IF NOT EXISTS idx_nl_snapshot ON notification_log (interpretation_snapshot_id)
    WHERE interpretation_snapshot_id IS NOT NULL;

-- issuer_cancelled | replaced | expired | not_listed (S1 F3 follow-up)
ALTER TABLE alert_post ADD COLUMN IF NOT EXISTS resolved_reason VARCHAR(32);
