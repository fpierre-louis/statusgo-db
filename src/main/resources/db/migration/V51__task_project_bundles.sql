-- V51: bundles / projects — a NEW `project_id` container link on `task`.
--
-- A project (kind='project') groups several child tasks for one recipient/home
-- (recipient label = title, location = the existing geo fields). Children point
-- at their container via project_id.
--
-- IMPORTANT: this is ENTIRELY distinct from the existing `parent_task_id`
-- column (V1 baseline), which is the repost / quote-post pointer wired to
-- PostService.withParentPosts. Do NOT conflate the two — bundles use project_id;
-- reposts keep parent_task_id.
--
-- Safety (the key property): PURELY ADDITIVE — zero existing rows touched.
--   • project_id is nullable with no default → every existing task is project_id
--     = NULL = a standalone task, unchanged. No backfill, no UPDATE, no DROP.
--   • The self-FK is ON DELETE SET NULL: deleting a project container DETACHES
--     its children back to standalone (project_id → NULL); it never
--     cascade-destroys real work orders (with their assignees/photos/liability).
--   • Plain transactional CREATE INDEX — NOT CONCURRENTLY. The `task` table is
--     small, and CREATE INDEX CONCURRENTLY hits statement_timeout on the prod
--     RDS and leaves an INVALID index (the V-series trap). A plain index inside
--     the migration transaction is correct here.
--
-- Rollback: clean while NO task carries a project_id yet — i.e. BEFORE the FE
-- bundle UI ships and starts writing project links. To revert:
--     DROP INDEX IF EXISTS idx_task_project;
--     ALTER TABLE task DROP CONSTRAINT IF EXISTS fk_task_project;
--     ALTER TABLE task DROP COLUMN IF EXISTS project_id;
-- (Once children reference a project, dropping the column loses only those
-- links — the child rows themselves survive as standalone tasks.)

ALTER TABLE task ADD COLUMN project_id BIGINT;

ALTER TABLE task
    ADD CONSTRAINT fk_task_project
    FOREIGN KEY (project_id) REFERENCES task (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_task_project ON task (project_id);
