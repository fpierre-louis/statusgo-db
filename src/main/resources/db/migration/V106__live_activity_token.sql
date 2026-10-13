-- V106 — Live Activity push tokens (live_activities epic, P1, 2026-10-13).
--
-- One row per ActivityKit push token. `user_info.fcm_token` holds ONE token
-- per user (whichever device registered last), but an ActivityKit token is
-- per activity AND per device, and FCM needs the FCM registration token of the
-- SAME device to deliver it (apns.live_activity_token rides on message.token).
-- So each row pairs the two (docs/epics/live_activities/GAMEPLAN.md §3.5).
--
--   kind        situation | location_share | checkin_request
--   token_type  update (per activity) | push_to_start (per device, P2)
--   reference_id  groupId / sessionId; null for push_to_start
--   activity_id   Apple Activity.id; null for push_to_start
--   ended_at      set on end, dismissal, UNREGISTERED, or the hourly sweep
--
-- Written by PUT /api/live-activities/tokens (upsert by apns_token, owner from
-- the verified Firebase token) and DELETE /api/live-activities/tokens/{activityId}.
-- Rows ended more than 7 days ago are deleted by the existing hourly
-- ActivationExpirySweepService; no new scheduler.
--
-- The partial index cannot be declared on the entity and H2 cannot build it
-- (SYSTEM_TRAPS T-2); the unique constraint IS declared on the entity, so the
-- test schema enforces the upsert key too.
--
-- Plain transactional DDL (no CONCURRENTLY — prod RDS times out).

CREATE TABLE IF NOT EXISTS live_activity_token (
    id            BIGSERIAL    PRIMARY KEY,
    user_email    VARCHAR(320) NOT NULL,
    kind          VARCHAR(32)  NOT NULL,
    token_type    VARCHAR(16)  NOT NULL,
    reference_id  VARCHAR(128) NULL,
    activity_id   VARCHAR(64)  NULL,
    apns_token    VARCHAR(256) NOT NULL,
    fcm_token     VARCHAR(512) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at      TIMESTAMPTZ  NULL,
    CONSTRAINT uk_live_activity_token_apns UNIQUE (apns_token),
    CONSTRAINT ck_live_activity_token_kind
        CHECK (kind IN ('situation', 'location_share', 'checkin_request')),
    CONSTRAINT ck_live_activity_token_type
        CHECK (token_type IN ('update', 'push_to_start'))
);

CREATE INDEX IF NOT EXISTS idx_live_activity_token_live_ref
    ON live_activity_token (kind, reference_id)
    WHERE ended_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_live_activity_token_user_type
    ON live_activity_token (user_email, token_type);
