-- V37 — Activate B2B agency seat management.
--
-- The Org/Group billing rail (plan_tier + stripe_customer_id /
-- stripe_subscription_id / subscription_status on `groups`) already exists from
-- the V1 baseline. This migration adds the SEAT CAP that lets large agencies
-- pay-per-seat via the web checkout: the Stripe webhook writes `max_seats` from
-- the subscription line-item quantity, and GroupService enforces it on member
-- add (409 when full).
--
-- Notes:
--   * `plan_tier` is already a free-form VARCHAR with no CHECK constraint, so
--     AGENCY / BUSINESS are already valid values — no constraint change needed.
--   * Additive + idempotent (ADD COLUMN IF NOT EXISTS). ddl-auto=validate stays
--     satisfied because the matching Group.maxSeats entity field ships with this.
--   * `allocated_seats` (seats currently used) is derived at read time from the
--     member roster size, so it is intentionally NOT stored.
--   * Plain transactional ALTER on a small table — no CREATE INDEX CONCURRENTLY,
--     so it cannot hit the prod statement_timeout trap.

ALTER TABLE groups ADD COLUMN IF NOT EXISTS max_seats INTEGER;
