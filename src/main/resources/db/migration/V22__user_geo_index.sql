-- flyway:executeInTransaction=false
-- Admin console Phase 3: bounding-box pre-filter for radius recipient counts/sends.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_info_geo
    ON user_info (last_known_lat, last_known_lng, last_known_location_at)
    WHERE last_known_lat IS NOT NULL AND last_known_lng IS NOT NULL;
