-- Location-share on group posts (household chat §D).
-- Adds an optional shared location to the `post` table (which backs the
-- GroupPost entity — see the Post/GroupPost naming table in CLAUDE.md).
-- All nullable: only "Share location" posts populate them; every existing
-- and future text/image post leaves them NULL. ddl-auto=validate requires
-- these columns to exist before the entity's new fields boot.

ALTER TABLE post ADD COLUMN IF NOT EXISTS latitude        DOUBLE PRECISION;
ALTER TABLE post ADD COLUMN IF NOT EXISTS longitude       DOUBLE PRECISION;
ALTER TABLE post ADD COLUMN IF NOT EXISTS location_label  VARCHAR(255);
