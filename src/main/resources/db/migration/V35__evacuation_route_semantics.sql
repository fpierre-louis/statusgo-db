-- Evacuation route semantics (FEMA/Red Cross final pillar): a household must
-- know HOW it gets to its destination, have an alternate route for blocked
-- roads, and keep offline maps for cell outages. Additive columns on the
-- evacuation_plan table (created in V1__baseline.sql).
--
--   primary_route_notes    BASELINE  — a set primary route is a required part of
--                                      the Evacuation Plan readiness pillar.
--   alternate_route_notes  ADVANCED  — a backup way out; tracked as an advanced
--                                      metric that never lowers baseline readiness.
--   offline_map_saved      ADVANCED  — maps downloaded to the phone for no-signal
--                                      evacuation; advanced metric.
--   last_practiced_at      when the household last rehearsed the route (nullable;
--                                      no writer yet — plumbed for a future drill hook).
ALTER TABLE evacuation_plan ADD COLUMN IF NOT EXISTS primary_route_notes   TEXT;
ALTER TABLE evacuation_plan ADD COLUMN IF NOT EXISTS alternate_route_notes TEXT;
ALTER TABLE evacuation_plan ADD COLUMN IF NOT EXISTS offline_map_saved     BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE evacuation_plan ADD COLUMN IF NOT EXISTS last_practiced_at     TIMESTAMP;
