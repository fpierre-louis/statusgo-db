-- Community map Phase 1 (docs/COMMUNITY_API_GAMEPLAN.md §4.2): composite
-- B-tree indexes over (latitude, longitude) so the viewport-driven
-- /api/community/map endpoint can range-scan the visible rectangle instead of
-- the current full-table scan (CommunityDiscoverService.findAll().stream()).
--
-- Plain, TRANSACTIONAL CREATE INDEX (not CONCURRENTLY): the target tables are
-- small (groups ~10^2, task ~10^3 rows) so the build is sub-second and the
-- brief SHARE lock on writes is fine pre-launch. A prior CONCURRENTLY attempt
-- hit the app connection's statement_timeout during its wait-for-transactions
-- phase and left an INVALID index behind; a plain transactional build avoids
-- both problems (fast, and rolls back cleanly on any error — no invalid index).

CREATE INDEX IF NOT EXISTS idx_groups_lat_lng
    ON groups (latitude, longitude)
    WHERE latitude IS NOT NULL AND longitude IS NOT NULL;

-- Community feed posts (table `task`) — the mutual-aid map layer filters
-- offer/marketplace rows with coords inside the viewport.
CREATE INDEX IF NOT EXISTS idx_task_lat_lng
    ON task (latitude, longitude)
    WHERE latitude IS NOT NULL AND longitude IS NOT NULL;
