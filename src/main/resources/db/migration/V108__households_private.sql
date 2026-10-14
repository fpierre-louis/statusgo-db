-- B2 / JN-03, owner decision D4 (approved 2026-10-13): every Household is Private.
--
-- A household is a family and its home. Three legacy household rows on prod
-- carried privacy = 'Public' (859, 332, 601; read-only count 2026-10-14:
-- Private 84, Public 3, none null). 'Public' made a household instantly
-- joinable by any signed-in account and put it, with its street address, on
-- Discover and the anonymous /preview. The code now refuses households on all
-- three paths regardless of this flag, and create already forces Private (B1);
-- this back-fill makes the stored value agree so nothing reads it differently.
--
-- Idempotent: re-running changes nothing (only rows not already 'Private').
-- Plain transactional DML on a small table (no CONCURRENTLY; prod RDS times out).
-- V108, not V107: V107 is reserved by the alerts lane.

UPDATE groups
   SET privacy = 'Private',
       updated_at = NOW()
 WHERE LOWER(COALESCE(group_type, '')) = 'household'
   AND (privacy IS NULL OR privacy <> 'Private');
