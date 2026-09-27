ALTER TABLE verification_application
    ADD COLUMN IF NOT EXISTS applicant_status_note VARCHAR(1000);
ALTER TABLE verification_application
    ADD COLUMN IF NOT EXISTS status_token_hash VARCHAR(64);
ALTER TABLE verification_application
    ADD COLUMN IF NOT EXISTS status_token_expires_at TIMESTAMP;
ALTER TABLE verification_application
    ADD COLUMN IF NOT EXISTS status_token_revoked_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_verification_app_status_token
    ON verification_application (status_token_hash)
    WHERE status_token_hash IS NOT NULL;
