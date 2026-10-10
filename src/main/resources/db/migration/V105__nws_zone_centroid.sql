-- V105 — resolved NWS zone centres, kept across restarts (2026-10-09).
--
-- The alert dispatcher places a zone-only alert (82% of NWS alerts ship no
-- polygon) at a zone's centre. Those centres came only from an in-memory cache
-- warmed at 100 per 5-minute tick, so every deploy or dyno restart emptied it
-- and zone-only alerts stopped reaching the community feed for up to an hour
-- (measured on prod: the Wasatch Front Flood Watch). NwsZoneService now loads
-- this table at startup and adds a row whenever it resolves a zone.
--
-- A zone's boundary changes on NWS's own schedule (rarely), so a stored centre
-- is a cache, not a fact: resolved_at lets a later pass refresh old rows.
--
-- Plain transactional DDL (no CONCURRENTLY — prod RDS times out). Small table:
-- at most ~8k zones nationwide.

CREATE TABLE IF NOT EXISTS nws_zone_centroid (
    ugc          VARCHAR(16)      PRIMARY KEY,
    latitude     DOUBLE PRECISION NOT NULL,
    longitude    DOUBLE PRECISION NOT NULL,
    resolved_at  TIMESTAMPTZ      NOT NULL
);
