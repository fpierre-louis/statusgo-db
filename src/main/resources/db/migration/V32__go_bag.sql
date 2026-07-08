-- Go bags — household-owned 72-hour evacuation kits + their checklist
-- items (docs/features/GO_BAG_WIZARD_SPEC.md). Ids are client-supplied
-- UUIDs (offline-create, same convention as household_manual_member).
-- Plain transactional DDL — NO "CREATE INDEX CONCURRENTLY" (prod RDS
-- statement_timeout cancels it and leaves an INVALID index; see the
-- V30 header for the incident write-up).

CREATE TABLE IF NOT EXISTS go_bag (
    id                 VARCHAR(64) PRIMARY KEY,
    household_id       VARCHAR(64) NOT NULL,
    owner_email        VARCHAR(255),
    name               VARCHAR(120) NOT NULL,
    kind               VARCHAR(24) NOT NULL,
    storage_label      VARCHAR(255),
    lat                DOUBLE PRECISION,
    lng                DOUBLE PRECISION,
    strategy           VARCHAR(24),
    premade_kit_label  VARCHAR(255),
    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_go_bag_household ON go_bag (household_id);

CREATE TABLE IF NOT EXISTS go_bag_item (
    id                VARCHAR(64) PRIMARY KEY,
    bag_id            VARCHAR(64) NOT NULL REFERENCES go_bag (id) ON DELETE CASCADE,
    item_key          VARCHAR(80) NOT NULL,
    product_key       VARCHAR(80),
    label             VARCHAR(160) NOT NULL,
    category          VARCHAR(32) NOT NULL,
    priority          INTEGER NOT NULL DEFAULT 1,
    qty_recommended   INTEGER NOT NULL DEFAULT 1,
    qty_packed        INTEGER NOT NULL DEFAULT 0,
    unit              VARCHAR(24),
    expires_on        DATE,
    reminder_sent_at  TIMESTAMP,
    notes             VARCHAR(500),
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_go_bag_item_bag ON go_bag_item (bag_id);

-- Covering index for the daily expiry sweep: only dated rows whose
-- reminder hasn't fired yet are ever scanned.
CREATE INDEX IF NOT EXISTS idx_go_bag_item_expiry
    ON go_bag_item (expires_on)
    WHERE expires_on IS NOT NULL AND reminder_sent_at IS NULL;
