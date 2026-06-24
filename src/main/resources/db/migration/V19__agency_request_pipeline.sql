-- Phase 2 admin console — agency request ownership and inbound source fields.
-- Keeps VerificationApplication as the single request/application state machine.

ALTER TABLE verification_application
    ALTER COLUMN group_id DROP NOT NULL;

ALTER TABLE verification_application
    ADD COLUMN IF NOT EXISTS assigned_consultant_email VARCHAR(320);
ALTER TABLE verification_application
    ADD COLUMN IF NOT EXISTS submitter_email VARCHAR(320);
ALTER TABLE verification_application
    ADD COLUMN IF NOT EXISTS stated_jurisdiction VARCHAR(400);
ALTER TABLE verification_application
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) DEFAULT 'REQUEST';

CREATE INDEX IF NOT EXISTS idx_verif_assignee
    ON verification_application (assigned_consultant_email);
CREATE INDEX IF NOT EXISTS idx_verif_submitter
    ON verification_application (submitter_email);
