-- V16 — civic-report → work-order linkage (Phase 5 Slice H).
-- An agency work order (a task-kind Post) created off a civic report
-- carries the source civic-report post id so the operational task links
-- back to the public card that prompted it. The community feed's Post
-- entity is backed by the physical `task` table.
ALTER TABLE task ADD COLUMN IF NOT EXISTS source_post_id BIGINT;

-- Lets us find every work order spawned from a given civic report.
CREATE INDEX IF NOT EXISTS idx_task_source_post ON task (source_post_id);
