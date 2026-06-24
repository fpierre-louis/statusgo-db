-- Phase 1 admin console foundation — platform-level RBAC.
-- Grants are keyed by lowercase email and may exist before a user signs up.

CREATE TABLE IF NOT EXISTS platform_admin (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(320) NOT NULL,
    role        VARCHAR(24)  NOT NULL DEFAULT 'NONE',
    granted_by  VARCHAR(320),
    granted_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_platform_admin_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS platform_admin_grant (
    platform_admin_id BIGINT NOT NULL REFERENCES platform_admin(id) ON DELETE CASCADE,
    permission        VARCHAR(40) NOT NULL,
    CONSTRAINT uk_platform_admin_grant UNIQUE (platform_admin_id, permission)
);

CREATE INDEX IF NOT EXISTS idx_platform_admin_grant
    ON platform_admin_grant (platform_admin_id);
