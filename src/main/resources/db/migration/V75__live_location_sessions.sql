CREATE TABLE IF NOT EXISTS live_location_sessions (
    id              VARCHAR(36) PRIMARY KEY,
    user_email      VARCHAR(255) NOT NULL,
    scope_type      VARCHAR(32)  NOT NULL DEFAULT 'group',
    activation_id   VARCHAR(255),
    alert_id        VARCHAR(255),
    started_at      TIMESTAMP    NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    stopped_at      TIMESTAMP,
    upload_token_hash VARCHAR(96),
    created_by_user BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS live_location_session_groups (
    session_id VARCHAR(36)  NOT NULL,
    group_id   VARCHAR(255) NOT NULL,
    CONSTRAINT fk_live_location_session_groups_session
        FOREIGN KEY (session_id) REFERENCES live_location_sessions(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_live_location_session_groups
    ON live_location_session_groups (session_id, group_id);

CREATE INDEX IF NOT EXISTS idx_live_location_session_groups_group
    ON live_location_session_groups (group_id);

CREATE INDEX IF NOT EXISTS idx_live_location_sessions_user_active
    ON live_location_sessions (LOWER(user_email), stopped_at, expires_at);

CREATE TABLE IF NOT EXISTS live_location_points (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(36)  NOT NULL,
    user_email  VARCHAR(255) NOT NULL,
    lat         DOUBLE PRECISION NOT NULL,
    lng         DOUBLE PRECISION NOT NULL,
    accuracy_m  DOUBLE PRECISION,
    speed_mps   DOUBLE PRECISION,
    heading_deg DOUBLE PRECISION,
    captured_at TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT fk_live_location_points_session
        FOREIGN KEY (session_id) REFERENCES live_location_sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_live_location_points_session_captured
    ON live_location_points (session_id, captured_at DESC);
