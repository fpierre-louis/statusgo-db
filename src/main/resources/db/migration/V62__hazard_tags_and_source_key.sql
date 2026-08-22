-- Tagging — three typed channels replace one untyped column.
--
-- `task_tags` was carrying three unrelated things in one Set<String>, which the
-- production data shows plainly (12,135 posts, measured 2026-08-22):
--
--     system-alert    12,126 rows   provenance  (written by AlertDispatchService)
--     nws             12,097        provenance  (source feed)
--     flood           10,386        hazard
--     tornado          1,711        hazard
--     usgs                29        provenance
--     earthquake          29        hazard
--     pillar:supplies      1        a fourth writer's namespaced idea
--
-- Every machine post carries EXACTLY three tags: `system-alert`, one source,
-- one hazard. Not one row has two sources or two hazards — which is why
-- `source_key` is a scalar and `task_hazard_tags` is a set: the first is
-- single-valued in the data AND in the domain, the second is single-valued in
-- the data only (an NWS alert can be both a flood and a wind event).
--
-- The `pillar:supplies` row is the tell. Someone reached for a NAMESPACE
-- because the column had none — one row, on the owner's own post, of a fourth
-- writer's idea. That is the ResourceCategory failure caught a step earlier,
-- while it is still one row instead of a vocabulary.
--
-- THE VOCABULARY LIVES IN JAVA, not in a CHECK constraint. `HazardType` is read
-- by AskService, the dispatcher and (next) the FE. A Postgres CHECK is
-- invisible to the second writer, which is exactly how the resource board ended
-- up with two vocabularies — and how FOUR hazard vocabularies accumulated here
-- before anyone noticed `heat` and `extreme_heat` were the same hazard.
--
-- THIS MIGRATION IS ADDITIVE. It creates the new channels and BACKFILLS them
-- from the tags already present. It deletes NOTHING: `task_tags` keeps all
-- 36,379 of its rows. Removing the machine rows is a separate, hard-gated
-- migration held for owner approval, and it is safe to hold precisely because
-- the dispatcher stops WRITING them in the same deploy as this file — so the
-- old rows stop growing the moment this ships, whether or not they are ever
-- deleted.
--
-- DRY-RUN RESULT against prod, inside a rolled-back transaction:
--   INSERT 0 12126   hazard rows   (flood 10,386 · tornado 1,711 · earthquake 29)
--   INSERT 0 0       alias rows    (no extreme_heat in task_tags, as predicted)
--   UPDATE 12126     source_key    (nws 12,097 · usgs 29)
--   0 posts left with a hazard and no source — the two channels are perfectly
--   correlated, which is what "every machine post has exactly three tags"
--   predicted and is the check that would have caught a wrong WHERE clause.

CREATE TABLE IF NOT EXISTS task_hazard_tags (
    task_id BIGINT      NOT NULL REFERENCES task (id) ON DELETE CASCADE,
    hazard  VARCHAR(32) NOT NULL,
    PRIMARY KEY (task_id, hazard)
);

CREATE INDEX IF NOT EXISTS idx_task_hazard_tags_hazard ON task_hazard_tags (hazard);

ALTER TABLE task ADD COLUMN IF NOT EXISTS source_key VARCHAR(24);
CREATE INDEX IF NOT EXISTS idx_task_source_key ON task (source_key);

-- ── Backfill ──────────────────────────────────────────────────────────────
-- Derived from `task_tags` at apply time rather than from a snapshot, because
-- the dispatcher runs every five minutes and the counts moved by 2 between the
-- audit read and this file being written. ON CONFLICT makes it idempotent, so
-- a re-run (or a Flyway repair) cannot double-insert.
--
-- The hazard list is the intersection of HazardType's vocabulary with what
-- actually appears in `task_tags`. Spelled out rather than pattern-matched: a
-- LIKE would sweep up `pillar:supplies` on the day someone adds
-- `pillar:flood`.
INSERT INTO task_hazard_tags (task_id, hazard)
SELECT DISTINCT tt.task_id, tt.tag
  FROM task_tags tt
 WHERE tt.tag IN ('hurricane','wildfire','earthquake','blizzard','flood',
                  'tornado','heat','smoke','other')
ON CONFLICT (task_id, hazard) DO NOTHING;

-- `extreme_heat` has never appeared in `task_tags` (RiskProfileService does not
-- write posts), but it is handled anyway so the backfill and HazardType.parse
-- agree about aliases. Cheap insurance against a future writer.
INSERT INTO task_hazard_tags (task_id, hazard)
SELECT DISTINCT tt.task_id, 'heat'
  FROM task_tags tt
 WHERE tt.tag IN ('extreme_heat','extreme-heat','excessive_heat')
ON CONFLICT (task_id, hazard) DO NOTHING;

-- Source. Only rows that have no source_key yet, so a re-run is a no-op and a
-- value written by the new dispatcher code is never overwritten by the old tag.
UPDATE task t
   SET source_key = s.tag
  FROM (SELECT DISTINCT task_id, tag FROM task_tags WHERE tag IN ('nws','usgs')) s
 WHERE s.task_id = t.id
   AND t.source_key IS NULL;

COMMENT ON TABLE task_hazard_tags IS
    'Hazard classification per post. Vocabulary: constant/HazardType.java (Java, '
    'not a CHECK — a CHECK is invisible to the second writer).';
COMMENT ON COLUMN task.source_key IS
    'Provenance: nws | usgs | agency | user. Scalar — every machine post in prod '
    'carries exactly one source.';
