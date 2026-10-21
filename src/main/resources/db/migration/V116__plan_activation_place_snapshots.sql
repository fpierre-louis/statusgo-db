-- Evacuation plan clarity / plan data consistency (2026-10-21).
-- A live plan keeps a SNAPSHOT of the meeting spot and destination it was
-- started (or last changed) with. Before this, every read resolved the saved
-- row by id, so editing a saved place silently changed a live instruction and
-- deleting one blanked it. Written on create and on every PATCH; null on rows
-- from before this migration (reads fall back to the saved row).
ALTER TABLE plan_activations ADD COLUMN IF NOT EXISTS meeting_snapshot jsonb;
ALTER TABLE plan_activations ADD COLUMN IF NOT EXISTS evac_snapshot jsonb;
