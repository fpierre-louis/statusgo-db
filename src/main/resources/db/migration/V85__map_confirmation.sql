-- V85 — "Still here?" confirmations for map places. Map-ideal backend BE-7
-- (docs/epics/map-ideal-backend/EXEC.md).
--
-- Why: the map wants "6 neighbours confirmed · 2h ago" on a cooling center or
-- an offer. The only existing signal, post_confirm, means "Me too" on a post —
-- a different fact (agreement with a report), and resources and OSM places had
-- nothing at all. This is a separate table ON PURPOSE: a "still here" tap must
-- never inflate a "me too" count, or the reverse.
--
-- One row per (target, person). Re-confirming UPDATES confirmed_at rather than
-- adding a row, so a count is a count of distinct people by construction. The
-- API refuses a re-confirm inside 10 minutes (429); reads count people who
-- confirmed in the last 7 days.
--
-- target_type: 'resource' (resource_listing.id), 'post' (community task.id),
-- 'osm' ("node/123" | "way/…" | "relation/…"). target_id is text so all three
-- fit one column; the service validates each form before writing.
--
-- Plain transactional DDL; new, empty table. Postgres-only; never runs under
-- the H2 test profile.

CREATE TABLE IF NOT EXISTS map_confirmation (
    id            BIGSERIAL    PRIMARY KEY,
    target_type   VARCHAR(16)  NOT NULL,
    target_id     VARCHAR(128) NOT NULL,
    user_email    VARCHAR(255) NOT NULL,
    confirmed_at  TIMESTAMP    NOT NULL,
    CONSTRAINT ck_map_confirmation_target_type
        CHECK (target_type IN ('resource', 'post', 'osm'))
);

-- Identity of a confirmation, and the lookup the write path makes.
CREATE UNIQUE INDEX IF NOT EXISTS uk_map_confirmation_target_user
    ON map_confirmation (target_type, target_id, user_email);

-- The read: recent confirmations for a batch of targets.
CREATE INDEX IF NOT EXISTS idx_map_confirmation_target_time
    ON map_confirmation (target_type, target_id, confirmed_at);
