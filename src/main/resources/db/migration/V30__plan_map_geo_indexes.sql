-- Map UI V2 foundation (docs/map/MAP_API_CONTRACT.md, DATABASE_AUDIT finding
-- D-3): composite B-tree indexes over (lat, lng) on the three household-plan
-- geo tables. Today these rows are read by owner_email / household_id; a future
-- bbox map layer over a household's meeting places / origins / shelters would
-- otherwise table-scan. Land the index BEFORE any viewport read over them.
--
-- Plain, TRANSACTIONAL CREATE INDEX (NOT CONCURRENTLY): these tables are small
-- (pre-launch/beta scale) so the build is sub-second and the brief lock is
-- fine. Learned from V22/V28: CREATE INDEX CONCURRENTLY on prod RDS hits the
-- app connection's statement_timeout during its wait-for-transactions phase and
-- leaves an INVALID index behind. Do NOT add a `-- flyway:executeInTransaction
-- =false` header — this must run inside Flyway's transaction.
--
-- Load-bearing: MeetingPlace / EvacuationPlan / OriginLocation map their
-- coordinate columns as bare `lat` / `lng` (no @Column(name=...) override),
-- NOT `latitude` / `longitude` like groups / user_info. Index the real columns.

CREATE INDEX IF NOT EXISTS idx_meeting_place_lat_lng
    ON meeting_place (lat, lng)
    WHERE lat IS NOT NULL AND lng IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_evacuation_plan_lat_lng
    ON evacuation_plan (lat, lng)
    WHERE lat IS NOT NULL AND lng IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_origin_location_lat_lng
    ON origin_location (lat, lng)
    WHERE lat IS NOT NULL AND lng IS NOT NULL;
