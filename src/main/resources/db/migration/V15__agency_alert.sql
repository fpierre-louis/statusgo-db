-- Phase 5 Slice D — geo-targeted agency alert send record (2026-06-15).
--
-- One row per agency alert sent. The UNIQUE dedup_key is the double-send
-- guard (a duplicate city-wide push is trust-destroying): a second send with
-- the same client idempotency key — or the same content inside a 10-minute
-- window — collides here and is rejected before re-dispatch. recipient_count
-- / post_id record what actually went out.
--
-- Additive + idempotent.

CREATE TABLE IF NOT EXISTS agency_alert (
    id                 BIGSERIAL PRIMARY KEY,
    publisher_group_id VARCHAR(80) NOT NULL,
    dedup_key          VARCHAR(160) NOT NULL,
    title              VARCHAR(200),
    body               VARCHAR(2000),
    official_tier      VARCHAR(16),
    affected_zips      VARCHAR(2000),
    post_id            BIGINT,
    recipient_count    INTEGER,
    created_by         VARCHAR(320),
    dispatched_at      TIMESTAMP,
    created_at         TIMESTAMP NOT NULL,
    CONSTRAINT uk_agency_alert_dedup UNIQUE (dedup_key)
);
CREATE INDEX IF NOT EXISTS idx_agency_alert_group ON agency_alert (publisher_group_id, created_at);
