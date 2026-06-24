-- Admin console Phase 3: agency point/radius authorization.
-- Radius values are stored in miles; geo math converts to km at the Java boundary.

ALTER TABLE groups ADD COLUMN IF NOT EXISTS jurisdiction_lat DOUBLE PRECISION;
ALTER TABLE groups ADD COLUMN IF NOT EXISTS jurisdiction_lng DOUBLE PRECISION;
ALTER TABLE groups ADD COLUMN IF NOT EXISTS jurisdiction_radius_mi DOUBLE PRECISION;
ALTER TABLE groups ADD COLUMN IF NOT EXISTS agency_authorized BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE verification_application ADD COLUMN IF NOT EXISTS draft_lat DOUBLE PRECISION;
ALTER TABLE verification_application ADD COLUMN IF NOT EXISTS draft_lng DOUBLE PRECISION;
ALTER TABLE verification_application ADD COLUMN IF NOT EXISTS draft_radius_mi DOUBLE PRECISION;
