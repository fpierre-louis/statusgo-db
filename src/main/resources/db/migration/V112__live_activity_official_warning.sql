-- P3b (live_activities EXEC-P3b-official-warning.md, owner go 2026-10-19):
-- the official warning card is a fourth Live Activity token kind. Its update
-- tokens follow an alert thread (reference_id = alert_thread_key).
ALTER TABLE live_activity_token DROP CONSTRAINT IF EXISTS ck_live_activity_token_kind;
ALTER TABLE live_activity_token ADD CONSTRAINT ck_live_activity_token_kind
    CHECK (kind IN ('situation', 'location_share', 'checkin_request', 'official_warning'));
