-- V38 — Population-Band + SMS-Usage pricing model.
--
-- The pricing shift is from strict per-seat to a POPULATION BAND (addressable
-- population cap) + a bundled monthly SMS allowance. We reuse the existing
-- `max_seats` column (V37) to represent the Population Band Cap (avoiding schema
-- thrash — no rename), and add only the SMS allowance here.
--
--   * max_seats            = Population Band Cap (already exists; unchanged)
--   * sms_allowance_monthly = bundled monthly SMS alert allowance (NEW)
--
-- Additive + idempotent (ADD COLUMN IF NOT EXISTS); the matching
-- Group.smsAllowanceMonthly entity field ships with this so ddl-auto=validate
-- stays satisfied. Plain transactional ALTER on a small table — no
-- CREATE INDEX CONCURRENTLY, so it cannot hit the prod statement_timeout trap.

ALTER TABLE groups ADD COLUMN IF NOT EXISTS sms_allowance_monthly INTEGER;
