-- V111 — R19 push-targeting audit (Alerts lane, owner decision 2026-10-15).
--
-- One row per severe NWS push, COUNTS ONLY (no emails, no locations): who the
-- containment rule reached where they are, who it reached at home, how many
-- fell back to the old 80 km rule because their zones were not known yet, and
-- how the result compares with what the 80 km rule alone would have done.
-- Lets the owner review real targeting after the switch, durably (Heroku logs
-- keep minutes, not days). Small table; plain transactional index (see the
-- CONCURRENTLY-on-RDS trap).
CREATE TABLE alert_push_targeting_audit (
    id                  BIGSERIAL PRIMARY KEY,
    created_at          TIMESTAMP     NOT NULL,
    alert_ref           VARCHAR(320)  NOT NULL,
    basis               VARCHAR(32)   NOT NULL,
    reached_here        INTEGER       NOT NULL,
    reached_home        INTEGER       NOT NULL,
    fallback_proximity  INTEGER       NOT NULL,
    proximity_would     INTEGER       NOT NULL,
    added_vs_proximity  INTEGER       NOT NULL,
    dropped_vs_proximity INTEGER      NOT NULL
);

CREATE INDEX idx_aupta_created ON alert_push_targeting_audit (created_at);
