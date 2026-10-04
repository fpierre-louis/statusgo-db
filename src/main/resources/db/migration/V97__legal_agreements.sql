-- V97 — evidentiary legal agreement ledger (legal/consent epic, 2026-10-04).
--
-- One row per accepted document type so counsel can reason about versioned
-- consent independently for Terms, Privacy, Emergency Disclaimer, and
-- Community Guidelines. Anonymous Firebase guest sessions have a UID but may
-- not have an email; user_id/email are therefore nullable while firebase_uid
-- is required.

CREATE TABLE IF NOT EXISTS legal_agreements (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              VARCHAR(64),
    firebase_uid          VARCHAR(128) NOT NULL,
    user_email            VARCHAR(320),
    document_type         VARCHAR(48) NOT NULL,
    policy_version        VARCHAR(120) NOT NULL,
    effective_date        VARCHAR(32) NOT NULL,
    accepted_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    authentication_state  VARCHAR(24) NOT NULL,
    acceptance_surface    VARCHAR(120) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_legal_agreements_user
    ON legal_agreements (user_id, accepted_at);

CREATE INDEX IF NOT EXISTS idx_legal_agreements_firebase
    ON legal_agreements (firebase_uid, accepted_at);

CREATE INDEX IF NOT EXISTS idx_legal_agreements_doc_version
    ON legal_agreements (document_type, policy_version);

ALTER TABLE legal_agreements DROP CONSTRAINT IF EXISTS chk_legal_agreements_doc;
ALTER TABLE legal_agreements
    ADD CONSTRAINT chk_legal_agreements_doc
    CHECK (document_type IN ('TERMS', 'PRIVACY', 'EMERGENCY_DISCLAIMER', 'COMMUNITY_GUIDELINES'));

ALTER TABLE legal_agreements DROP CONSTRAINT IF EXISTS chk_legal_agreements_auth_state;
ALTER TABLE legal_agreements
    ADD CONSTRAINT chk_legal_agreements_auth_state
    CHECK (authentication_state IN ('ACCOUNT', 'GUEST'));
