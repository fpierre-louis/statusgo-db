-- Composer V2 follow-up (2026-09-28): the alert's own area, kept with the
-- auto-post it produced, so the feed card and the thread can draw the real
-- outline instead of an approximate box. GeoJSON Polygon/MultiPolygon text,
-- written at dispatch; NULL for zone-only alerts (no polygon on the wire)
-- and for rows dispatched before this column existed.
ALTER TABLE alert_post ADD COLUMN area_geojson TEXT;
