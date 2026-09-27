-- Durable dispatch state for agency-authored jurisdiction alerts.

ALTER TABLE agency_alert ADD COLUMN IF NOT EXISTS dispatch_status VARCHAR(16);
ALTER TABLE agency_alert ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agency_alert ADD COLUMN IF NOT EXISTS attempted_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agency_alert ADD COLUMN IF NOT EXISTS delivered_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agency_alert ADD COLUMN IF NOT EXISTS failed_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agency_alert ADD COLUMN IF NOT EXISTS last_error VARCHAR(1000);
ALTER TABLE agency_alert ADD COLUMN IF NOT EXISTS queued_at TIMESTAMP;
ALTER TABLE agency_alert ADD COLUMN IF NOT EXISTS started_at TIMESTAMP;
ALTER TABLE agency_alert ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;
ALTER TABLE agency_alert ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP;
ALTER TABLE agency_alert ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

UPDATE agency_alert
SET dispatch_status = 'SENT',
    attempt_count = 1,
    queued_at = COALESCE(queued_at, created_at),
    started_at = COALESCE(started_at, dispatched_at, created_at),
    completed_at = COALESCE(completed_at, dispatched_at, created_at),
    attempted_count = COALESCE(recipient_count, 0),
    delivered_count = COALESCE(recipient_count, 0),
    failed_count = 0
WHERE dispatch_status IS NULL;

ALTER TABLE agency_alert ALTER COLUMN dispatch_status SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agency_alert_dispatch
    ON agency_alert (dispatch_status, next_attempt_at, created_at);

CREATE TABLE IF NOT EXISTS agency_alert_recipient (
    alert_id        BIGINT       NOT NULL REFERENCES agency_alert (id) ON DELETE CASCADE,
    recipient_email VARCHAR(320) NOT NULL,
    PRIMARY KEY (alert_id, recipient_email)
);

CREATE INDEX IF NOT EXISTS idx_agency_alert_recipient_email
    ON agency_alert_recipient (recipient_email);

CREATE TABLE IF NOT EXISTS agency_alert_dispatch_attempt (
    id              BIGSERIAL    PRIMARY KEY,
    alert_id        BIGINT       NOT NULL REFERENCES agency_alert (id) ON DELETE CASCADE,
    attempt_number  INTEGER      NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempted_count INTEGER      NOT NULL DEFAULT 0,
    delivered_count INTEGER      NOT NULL DEFAULT 0,
    failed_count    INTEGER      NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000),
    started_at      TIMESTAMP    NOT NULL,
    completed_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uk_agency_alert_dispatch_attempt UNIQUE (alert_id, attempt_number)
);

CREATE INDEX IF NOT EXISTS idx_agency_alert_attempt_alert
    ON agency_alert_dispatch_attempt (alert_id, attempt_number);

INSERT INTO agency_alert_dispatch_attempt (
    alert_id, attempt_number, status, attempted_count, delivered_count,
    failed_count, started_at, completed_at
)
SELECT id, 1, 'SENT', attempted_count, delivered_count, failed_count,
       COALESCE(started_at, created_at), COALESCE(completed_at, created_at)
FROM agency_alert
WHERE dispatch_status = 'SENT'
ON CONFLICT (alert_id, attempt_number) DO NOTHING;
