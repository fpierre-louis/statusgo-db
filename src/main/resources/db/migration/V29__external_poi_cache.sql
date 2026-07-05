-- Community map Phase 2 (docs/COMMUNITY_API_GAMEPLAN.md §3.3, §4.4): the L2
-- cache for external POIs (Overpass parks/schools/civic amenities). Tile-keyed
-- so adjacent pans coalesce onto the same row; stale-while-revalidate reads it
-- first and refreshes asynchronously so the request never blocks on Overpass's
-- 2-30s latency.
--
-- Plain transactional CREATE (learned from V28: no CONCURRENTLY needed for a
-- brand-new table). `payload` is TEXT, not jsonb: it holds an opaque normalized
-- MapPoi[] JSON blob that is deserialized whole and never queried into — this
-- matches the codebase's existing JSON-blob convention (AskQuestion.body,
-- MealPlanData.selected_items_json use @JdbcTypeCode(LONGVARCHAR)+text) and
-- avoids Hibernate jsonb binding at ddl-auto=validate.

CREATE TABLE IF NOT EXISTS external_poi_cache (
    cache_key   varchar(160) PRIMARY KEY,   -- {source}:{snappedMinLat}:{minLng}:{maxLat}:{maxLng}
    source      varchar(32)  NOT NULL,      -- overpass | fema | nps
    payload     text         NOT NULL,      -- normalized MapPoi[] JSON
    fetched_at  timestamptz  NOT NULL,
    expires_at  timestamptz  NOT NULL
);

-- Sweep / staleness lookups by source + expiry.
CREATE INDEX IF NOT EXISTS idx_external_poi_cache_expires
    ON external_poi_cache (source, expires_at);
