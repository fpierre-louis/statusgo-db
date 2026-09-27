-- V83 — where a fix came from, how good it was, and which saved place it
-- falls inside. Map-ideal backend BE-2 (docs/epics/map-ideal-backend/EXEC.md).
--
-- Why: the roster could say WHERE someone is (lat/lng) but not the three things
-- the redesigned map needs to say it in words — "At school since 8:05",
-- "Last seen near Dry Creek", and whether the dot came from a phone or a watch.
-- Nothing captured any of them. All three are derived on the ONE write path
-- (PATCH /api/userinfo/me/location, plus live-location points), so a future
-- watch app feeds them by calling that same endpoint with source = 'watch'.
--
-- ADDITIVE ONLY. Every new column is nullable or carries a constant default, so
-- existing rows need no backfill and an old client that sends only {lat, lng}
-- keeps working (source/accuracy stay NULL). Plain transactional DDL — no
-- CREATE INDEX CONCURRENTLY (times out on prod RDS; see SYSTEM_TRAPS). No new
-- index: every read of these columns is by primary key or by the existing
-- owner_email index.
--
-- Postgres-only DDL. It never runs under the H2 test profile (flyway disabled
-- there; Hibernate builds the H2 schema from the entities).

-- ── user_info: the latest fix, described ────────────────────────────────────
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS location_source      VARCHAR(16);
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS location_accuracy_m  INTEGER;

-- The saved place the latest fix falls inside, and since when. Deliberately NOT
-- a foreign key: user_info rows are written with a stale in-memory copy on
-- every location ping, and an FK with ON DELETE SET NULL would turn a place
-- deleted mid-ping into a constraint violation (a 500) on the next save. The
-- read path re-checks that the place still exists, still belongs to the member
-- and still has share_presence = TRUE, so a dangling id renders nothing.
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS current_place_id     BIGINT;
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS current_place_since  TIMESTAMP;

-- Short reverse-geocoded label for the latest fix ("Dry Creek"), plus the point
-- it was resolved at. The anchor is what makes the ~2 mi refresh throttle
-- honest: measured against the PREVIOUS fix, a person moving in small steps
-- never crosses 2 mi between two pings and the label would go stale forever.
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS last_seen_near_label VARCHAR(160);
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS last_seen_near_lat   DOUBLE PRECISION;
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS last_seen_near_lng   DOUBLE PRECISION;

ALTER TABLE user_info DROP CONSTRAINT IF EXISTS ck_user_info_location_source;
ALTER TABLE user_info ADD CONSTRAINT ck_user_info_location_source
    CHECK (location_source IS NULL OR location_source IN ('phone', 'watch', 'web'));

ALTER TABLE user_info DROP CONSTRAINT IF EXISTS ck_user_info_location_accuracy_m;
ALTER TABLE user_info ADD CONSTRAINT ck_user_info_location_accuracy_m
    CHECK (location_accuracy_m IS NULL OR location_accuracy_m BETWEEN 1 AND 100000);

-- ── user_saved_location: what kind of place, and may others see "At <place>" ─
ALTER TABLE user_saved_location ADD COLUMN IF NOT EXISTS kind           VARCHAR(16);
-- Opt-in, per place, default OFF. Only a place with this TRUE can ever produce
-- "At <place>" for anyone else.
ALTER TABLE user_saved_location ADD COLUMN IF NOT EXISTS share_presence BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_saved_location ADD COLUMN IF NOT EXISTS radius_m       INTEGER NOT NULL DEFAULT 150;

ALTER TABLE user_saved_location DROP CONSTRAINT IF EXISTS ck_user_saved_location_kind;
ALTER TABLE user_saved_location ADD CONSTRAINT ck_user_saved_location_kind
    CHECK (kind IS NULL OR kind IN ('home', 'work', 'school', 'other'));

ALTER TABLE user_saved_location DROP CONSTRAINT IF EXISTS ck_user_saved_location_radius_m;
ALTER TABLE user_saved_location ADD CONSTRAINT ck_user_saved_location_radius_m
    CHECK (radius_m BETWEEN 50 AND 2000);

-- ── live_location_points: same optional source as the presence ping ─────────
ALTER TABLE live_location_points ADD COLUMN IF NOT EXISTS source VARCHAR(16);

ALTER TABLE live_location_points DROP CONSTRAINT IF EXISTS ck_live_location_points_source;
ALTER TABLE live_location_points ADD CONSTRAINT ck_live_location_points_source
    CHECK (source IS NULL OR source IN ('phone', 'watch', 'web'));
