ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS subscription_override_tier VARCHAR(64),
    ADD COLUMN IF NOT EXISTS subscription_override_expires_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS subscription_override_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS subscription_override_by VARCHAR(320),
    ADD COLUMN IF NOT EXISTS subscription_override_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_groups_subscription_override_expires
    ON groups (subscription_override_expires_at);

ALTER TABLE user_info
    ADD COLUMN IF NOT EXISTS subscription_override_package VARCHAR(64),
    ADD COLUMN IF NOT EXISTS subscription_override_expires_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS subscription_override_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS subscription_override_by VARCHAR(320),
    ADD COLUMN IF NOT EXISTS subscription_override_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_user_info_subscription_override_expires
    ON user_info (subscription_override_expires_at);

ALTER TABLE verification_application
    ADD COLUMN IF NOT EXISTS logo_image_url VARCHAR(1024);
