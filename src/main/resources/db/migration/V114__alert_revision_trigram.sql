-- Backlog build 10, Lane 1 (owner go 2026-10-20): the alert thread lookup
-- (AlertSourceRevisionThreadQueryImpl) matches a key inside the space-separated
-- vtec_event_keys / reference_ids lists with a leading-wildcard LIKE, which a
-- b-tree can't answer, so every lookup scanned the table. pg_trgm GIN indexes
-- answer '%key%'; the query rechecks the whole-token match on what they return.
-- Plain CREATE INDEX, not CONCURRENTLY: the table is small (8k rows, 25 MB on
-- 2026-10-20) and CONCURRENTLY times out under Flyway on prod (SYSTEM_TRAPS).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_asr_vtec_trgm ON alert_source_revision USING gin (vtec_event_keys gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_asr_refs_trgm ON alert_source_revision USING gin (reference_ids gin_trgm_ops);
