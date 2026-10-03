-- What a plan's starting point IS (open-items plan 2.1, 2026-10-02):
-- home | work | school | other, or NULL for one typed freehand.
--
-- "Home" was stored five ways; a starting point named "Home" was seeded once
-- from the household's address and then never followed it, so a household that
-- moved showed a stale second "Home" on the map. A starting point of kind
-- `home` now takes its location from the household on read (derived, not
-- stored), and the maps draw the household's own pin for it once.
ALTER TABLE origin_location ADD COLUMN IF NOT EXISTS kind VARCHAR(16);
-- Existing rows the quick label created: "Home" verbatim.
UPDATE origin_location SET kind = 'home'
 WHERE kind IS NULL AND LOWER(TRIM(name)) = 'home';
