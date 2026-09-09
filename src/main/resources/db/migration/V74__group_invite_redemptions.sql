CREATE TABLE IF NOT EXISTS group_invite_redemptions (
    id          BIGSERIAL PRIMARY KEY,
    invite_id   VARCHAR(255) NOT NULL,
    user_email  VARCHAR(255) NOT NULL,
    group_id    VARCHAR(255) NOT NULL,
    redeemed_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_group_invite_redemption_user UNIQUE (invite_id, user_email)
);

CREATE INDEX IF NOT EXISTS idx_group_invite_redemptions_user
    ON group_invite_redemptions (user_email);

CREATE INDEX IF NOT EXISTS idx_group_invite_redemptions_invite
    ON group_invite_redemptions (invite_id);
