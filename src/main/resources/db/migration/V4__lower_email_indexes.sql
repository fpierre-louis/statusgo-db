-- Functional indexes on LOWER(email) for IgnoreCase repository lookups — audit P1-11
--
-- Spring Data JPA derived queries that use the IgnoreCase suffix translate to
-- WHERE LOWER(column) = LOWER(?) at SQL emit time. Plain b-tree indexes on the
-- raw email column do not satisfy that predicate, so PostgreSQL falls back to
-- sequential scans on every owner_email / member_email lookup. The /me endpoint
-- aggregates across plan-builder, contact, and group-membership tables; each
-- IgnoreCase lookup pays full table scan cost without these.
--
-- Table names mirror the V1 baseline (evacuation_plan, meal_plan_data_v2,
-- group_member_emails / group_admin_emails / group_pending_member_emails,
-- emergency_contact_groups). origin_location uses user_email rather than
-- owner_email per V1.

CREATE INDEX IF NOT EXISTS idx_demographic_owner_lower
    ON demographic (LOWER(owner_email));

CREATE INDEX IF NOT EXISTS idx_evacuation_plan_owner_lower
    ON evacuation_plan (LOWER(owner_email));

CREATE INDEX IF NOT EXISTS idx_meal_plan_data_v2_owner_lower
    ON meal_plan_data_v2 (LOWER(owner_email));

CREATE INDEX IF NOT EXISTS idx_meeting_place_owner_lower
    ON meeting_place (LOWER(owner_email));

CREATE INDEX IF NOT EXISTS idx_origin_location_user_lower
    ON origin_location (LOWER(user_email));

CREATE INDEX IF NOT EXISTS idx_emergency_contact_groups_owner_lower
    ON emergency_contact_groups (LOWER(owner_email));

CREATE INDEX IF NOT EXISTS idx_group_member_emails_lower
    ON group_member_emails (LOWER(member_email));

CREATE INDEX IF NOT EXISTS idx_group_admin_emails_lower
    ON group_admin_emails (LOWER(admin_email));

CREATE INDEX IF NOT EXISTS idx_group_pending_member_emails_lower
    ON group_pending_member_emails (LOWER(pending_member_email));
