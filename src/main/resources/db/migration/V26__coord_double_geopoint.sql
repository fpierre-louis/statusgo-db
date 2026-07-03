-- V26 — Coordinate columns: varchar (String) -> double precision.
--
-- Backs the new GeoPoint embeddable on Group + UserInfo (Database Phase of
-- docs/MAP_REBUILD_PLAN.md). Hibernate runs with ddl-auto=validate, so this
-- migration and the matching entity type change MUST ship in the same deploy —
-- Flyway applies this as the first step of boot, then Hibernate validates the
-- (now double precision) columns against the GeoPoint mapping.
--
-- SAFETY / REVERSIBILITY
--   * Before touching the live columns we copy the raw text into
--     *_legacy_text shadow columns, so the original strings are preserved
--     verbatim and the change can be rolled back. These shadow columns are
--     unmapped by JPA (validate ignores extra columns) and are dropped in a
--     later migration once the conversion is proven in production.
--   * The USING clause parses only well-formed numbers. Any blank / malformed /
--     non-numeric legacy value becomes NULL — the correct "no location" state —
--     instead of aborting the migration. Nothing is corrupted.

-- ---- groups -------------------------------------------------------------
ALTER TABLE groups ADD COLUMN IF NOT EXISTS latitude_legacy_text  varchar(64);
ALTER TABLE groups ADD COLUMN IF NOT EXISTS longitude_legacy_text varchar(64);

UPDATE groups
   SET latitude_legacy_text  = latitude,
       longitude_legacy_text = longitude
 WHERE latitude_legacy_text IS NULL
   AND longitude_legacy_text IS NULL;

ALTER TABLE groups
    ALTER COLUMN latitude  TYPE double precision
        USING (CASE WHEN latitude  ~ '^[[:space:]]*-?[0-9]+(\.[0-9]+)?[[:space:]]*$'
                    THEN trim(latitude)::double precision  ELSE NULL END),
    ALTER COLUMN longitude TYPE double precision
        USING (CASE WHEN longitude ~ '^[[:space:]]*-?[0-9]+(\.[0-9]+)?[[:space:]]*$'
                    THEN trim(longitude)::double precision ELSE NULL END);

-- ---- user_info ----------------------------------------------------------
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS latitude_legacy_text  varchar(64);
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS longitude_legacy_text varchar(64);

UPDATE user_info
   SET latitude_legacy_text  = latitude,
       longitude_legacy_text = longitude
 WHERE latitude_legacy_text IS NULL
   AND longitude_legacy_text IS NULL;

ALTER TABLE user_info
    ALTER COLUMN latitude  TYPE double precision
        USING (CASE WHEN latitude  ~ '^[[:space:]]*-?[0-9]+(\.[0-9]+)?[[:space:]]*$'
                    THEN trim(latitude)::double precision  ELSE NULL END),
    ALTER COLUMN longitude TYPE double precision
        USING (CASE WHEN longitude ~ '^[[:space:]]*-?[0-9]+(\.[0-9]+)?[[:space:]]*$'
                    THEN trim(longitude)::double precision ELSE NULL END);
