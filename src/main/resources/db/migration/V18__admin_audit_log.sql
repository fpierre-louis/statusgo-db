-- Phase 1 admin console foundation — append-only audit trail.

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id           BIGSERIAL PRIMARY KEY,
    actor_email  VARCHAR(320) NOT NULL,
    action       VARCHAR(48)  NOT NULL,
    target_type  VARCHAR(32),
    target_id    VARCHAR(64),
    summary      VARCHAR(500),
    at           TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_actor
    ON admin_audit_log (actor_email, at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_target
    ON admin_audit_log (target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_audit_at
    ON admin_audit_log (at DESC);
