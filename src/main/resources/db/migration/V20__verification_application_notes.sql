-- Phase 2 admin console — append-only consultation log for each request.

CREATE TABLE IF NOT EXISTS verification_application_note (
    id             BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES verification_application(id) ON DELETE CASCADE,
    author_email   VARCHAR(320) NOT NULL,
    note           VARCHAR(1000) NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_verif_note_application
    ON verification_application_note (application_id, created_at);
