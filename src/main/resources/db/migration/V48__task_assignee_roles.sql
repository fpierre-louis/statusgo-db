-- V48 — Task role model + multi-assignee (Step 2 of the Work-Order overhaul).
--
-- Introduces the task_assignee collection: 0..N people assigned to a work order
-- (a kind='task' row on the `task` table), each with a task-level role of LEAD
-- or HELPER. This collection is the SOLE authority for assignment membership and
-- roles. `task.assignee_email` is demoted to a non-authoritative "primary
-- display" mirror, maintained write-through by the single assignment writer
-- (TaskAssignmentService); nothing reads it to determine a role. Full design +
-- locked decisions: docs/DOCS_STEP2_ROLE_MODEL_DESIGN.md.
--
-- Design notes:
--   * PURELY ADDITIVE — creates one new table + backfills it. Touches ZERO
--     columns on `task`. Rollback is therefore a clean `DROP TABLE task_assignee`
--     in a later migration, with no data loss (the `task` table is untouched).
--   * Plain transactional DDL on a new/empty table — NO CREATE INDEX
--     CONCURRENTLY, so it cannot hit the prod statement_timeout trap
--     (reference_flyway_concurrently_timeout).
--   * The matching entity (domain/TaskAssignee.java) + repo ship with this
--     migration so ddl-auto=validate stays satisfied at boot. The partial-unique
--     Lead index, the role CHECK, and the FK are Postgres-only (not expressible
--     as JPA annotations); the H2 test profile builds the table from the entity
--     via ddl-auto=create-drop and does NOT exercise them — they are validated
--     by the V48 rehearsal against real Postgres instead.
--   * BACKFILL = existing single assignee -> LEAD (owner-locked, review 2). This
--     preserves today's "the assignee runs it" continuity; a "-> HELPER" backfill
--     would leave every in-flight task Lead-less the instant this runs (nobody
--     could cancel/reassign on a live disaster-response system until a human
--     promoted a Lead). It is a DELIBERATE, BOUNDED one-time authority grant: the
--     migrated assignee gains cancel + assign rights they lacked under Step 1 —
--     bounded because they were already an Admin/Owner's chosen assignee.

-- ---------------------------------------------------------------------------
-- 1. The collection table
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS task_assignee (
    id           BIGSERIAL     PRIMARY KEY,
    -- FK to the work order. CASCADE: assignees die with their task.
    task_id      BIGINT        NOT NULL REFERENCES task (id) ON DELETE CASCADE,
    -- Stored already lower-cased + trimmed by the writer (and the backfill
    -- below), so the plain UNIQUE (task_id, email) below is effectively
    -- case-insensitive without a functional index.
    email        VARCHAR(255)  NOT NULL,
    role         VARCHAR(16)   NOT NULL,
    assigned_by  VARCHAR(255),
    assigned_at  TIMESTAMP,
    created_at   TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT ck_task_assignee_role CHECK (role IN ('LEAD', 'HELPER'))
);

-- ---------------------------------------------------------------------------
-- 2. Constraints + indexes
-- ---------------------------------------------------------------------------
-- A person appears at most once per task (no LEAD+HELPER duplication for the
-- same person; "promote/demote" is a role change on the one row).
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_assignee_task_email
    ON task_assignee (task_id, email);

-- The hard, DB-enforced "<= 1 LEAD per task" invariant. Partial (filtered)
-- unique index — Postgres-only; cannot be a JPA @UniqueConstraint.
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_assignee_one_lead
    ON task_assignee (task_id)
    WHERE role = 'LEAD';

-- Lookup: assignees for a task (DTO fold + derivePrimary).
CREATE INDEX IF NOT EXISTS idx_task_assignee_task
    ON task_assignee (task_id);

-- Lookup: tasks a person is assigned to (the "my work" assignee arm).
CREATE INDEX IF NOT EXISTS idx_task_assignee_email
    ON task_assignee (email);

-- ---------------------------------------------------------------------------
-- 3. Backfill — existing single assignee becomes the LEAD
-- ---------------------------------------------------------------------------
-- Scoped kind='task' (group/community work orders); personal to-dos and
-- non-work-order kinds are untouched. Email is trimmed + lower-cased to match
-- the assignment writer's normalization. The NOT EXISTS guard makes the
-- backfill safe to re-run (Flyway runs it once, but a partial-failure retry
-- won't double-insert).
INSERT INTO task_assignee (task_id, email, role, assigned_by, assigned_at, created_at)
SELECT t.id,
       lower(btrim(t.assignee_email)),
       'LEAD',
       t.assigned_by_email,
       t.assigned_at,
       COALESCE(t.assigned_at, now())
FROM   task t
WHERE  t.kind = 'task'
  AND  t.assignee_email IS NOT NULL
  AND  btrim(t.assignee_email) <> ''
  AND  NOT EXISTS (SELECT 1 FROM task_assignee ta WHERE ta.task_id = t.id);

-- ---------------------------------------------------------------------------
-- 4. Execution-time safety assertion (real-data guard)
-- ---------------------------------------------------------------------------
-- The backfill inserts exactly ONE LEAD row per assigned task (assignee_email is
-- single-valued, so it structurally cannot produce a (task_id, email) duplicate
-- or a second LEAD for a task) — the unique constraints above are un-violate-able
-- by it. This block is defense-in-depth for any UNFORESEEN data anomaly: if the
-- number of LEAD rows created does not equal the number of assigned kind='task'
-- rows, RAISE aborts the whole migration (Flyway wraps each migration in a
-- transaction on Postgres, so the CREATE TABLE + indexes + backfill all roll
-- back) rather than silently committing an under-populated table.
DO $$
DECLARE
    v_leads    BIGINT;
    v_expected BIGINT;
BEGIN
    SELECT count(*) INTO v_leads
      FROM task_assignee
      WHERE role = 'LEAD';
    SELECT count(*) INTO v_expected
      FROM task t
      WHERE t.kind = 'task'
        AND t.assignee_email IS NOT NULL
        AND btrim(t.assignee_email) <> '';
    IF v_leads <> v_expected THEN
        RAISE EXCEPTION
          'V48 backfill mismatch: % LEAD rows created but % assigned kind=task rows expected — aborting migration (possible constraint drop or data anomaly).',
          v_leads, v_expected;
    END IF;
END $$;
