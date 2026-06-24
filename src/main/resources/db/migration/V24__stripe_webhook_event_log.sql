CREATE TABLE IF NOT EXISTS stripe_webhook_event (
    id                BIGSERIAL PRIMARY KEY,
    stripe_event_id   VARCHAR(255) NOT NULL,
    event_type        VARCHAR(128) NOT NULL,
    live_mode         BOOLEAN NOT NULL DEFAULT FALSE,
    status            VARCHAR(32) NOT NULL,
    group_id          VARCHAR(64),
    detail            VARCHAR(1000),
    received_at       TIMESTAMP NOT NULL DEFAULT now(),
    processed_at      TIMESTAMP,
    CONSTRAINT uk_stripe_webhook_event_id UNIQUE (stripe_event_id)
);

CREATE INDEX IF NOT EXISTS idx_stripe_webhook_received
    ON stripe_webhook_event (received_at DESC);

CREATE INDEX IF NOT EXISTS idx_stripe_webhook_status_received
    ON stripe_webhook_event (status, received_at DESC);

CREATE INDEX IF NOT EXISTS idx_stripe_webhook_group
    ON stripe_webhook_event (group_id, received_at DESC);
