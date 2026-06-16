-- Phase 5 Slice C — jurisdiction data model + cached user zip (2026-06-15).
--
-- Foundation for agency geo-targeted alerts (Slice D): each verified agency
-- group claims a set of US zips it is authorized to target, and each user
-- caches their current full zip so recipients are an indexed set-membership
-- lookup (lastKnownZip IN agency.jurisdictionZips) instead of a Haversine
-- scan per send.
--
-- Matches the existing Group collection-table convention exactly
-- (group_admin_emails: group_id VARCHAR(255) + value column). Additive +
-- nullable + idempotent — safe on prod regardless of partial state.

-- 1. Cached full postcode on each user (UserInfo.lastKnownZip), indexed for
--    the recipient lookup.
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS last_known_zip VARCHAR(12);
CREATE INDEX IF NOT EXISTS idx_user_info_last_known_zip ON user_info (last_known_zip);

-- 2. Group jurisdiction scalars (Group.jurisdictionType / serviceAreaRadius).
ALTER TABLE groups ADD COLUMN IF NOT EXISTS jurisdiction_type   VARCHAR(24);
ALTER TABLE groups ADD COLUMN IF NOT EXISTS service_area_radius DOUBLE PRECISION;

-- 3. Group jurisdiction zip set (Group.jurisdictionZips @ElementCollection).
CREATE TABLE IF NOT EXISTS group_jurisdiction_zips (
    group_id VARCHAR(255) NOT NULL,
    zip      VARCHAR(12)
);
CREATE INDEX IF NOT EXISTS idx_group_jurisdiction_group ON group_jurisdiction_zips (group_id);
CREATE INDEX IF NOT EXISTS idx_group_jurisdiction_zip   ON group_jurisdiction_zips (zip);
