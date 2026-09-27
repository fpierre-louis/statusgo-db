-- V84 — opening hours on community resource listings. Map-ideal backend BE-6
-- (docs/epics/map-ideal-backend/EXEC.md).
--
-- Why: the map shows a cooling center as "open · until 9 PM". Nothing on
-- resource_listing recorded hours, so any open/closed claim was invented. This
-- column stores the listing's weekly schedule; the SERVER computes openNow /
-- closesAt / opensAt from it on every read (DST-safe, in the listing's own IANA
-- time zone). No hours → openNow is NULL, never assumed open.
--
-- Shape (validated by io.sitprep.sitprepapi.util.OpeningHours before write):
--   { "tz": "America/Denver",
--     "weekly": { "mon": [["09:00","21:00"]], "fri": [["18:00","02:00"]], ... },
--     "note": "Closes early on holidays" }
-- 0–3 ranges per day, HH:mm, "24:00" allowed as an end, a range may cross
-- midnight. Missing day = closed that day.
--
-- Native JSONB, the pattern of V40/V47 (@JdbcTypeCode(SqlTypes.JSON) +
-- columnDefinition = "jsonb"). ADDITIVE, nullable, no default, no backfill.
-- Plain transactional DDL. Postgres-only; never runs under the H2 test profile.

ALTER TABLE resource_listing ADD COLUMN IF NOT EXISTS hours_json JSONB;

ALTER TABLE resource_listing DROP CONSTRAINT IF EXISTS ck_resource_listing_hours_json_object;
ALTER TABLE resource_listing ADD CONSTRAINT ck_resource_listing_hours_json_object
    CHECK (hours_json IS NULL OR jsonb_typeof(hours_json) = 'object');
