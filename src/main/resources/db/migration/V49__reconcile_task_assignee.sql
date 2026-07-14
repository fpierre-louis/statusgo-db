-- V49 — ONE-TIME reconciliation of task_assignee from the authoritative
-- assignee_email column (Step 2 of the Work-Order overhaul).
--
-- Context: V48 created + backfilled task_assignee, but the table was applied to
-- prod out-of-band (2026-07-14; see docs/architecture/SYSTEM_TRAPS_AND_PATTERNS.md
-- T-1) and has since DRIFTED — the pre-Step-2 bulk @Modifying transitions
-- (transitionAssign / transitionCancel / transitionClaim) change/clear
-- assignee_email WITHOUT touching task_assignee, so the backfilled LEAD rows no
-- longer match reality (e.g. task 8146 has a LEAD row but an empty assignee_email).
--
-- Why a migration and NOT app-startup code (DOCS_STEP2 Decision 1): startup runs
-- on EVERY boot/dyno restart. A startup "rebuild from assignee_email" would DELETE
-- every HELPER the first time the app restarts after Helpers exist (assignee_email
-- only knows the Lead). A Flyway migration runs EXACTLY ONCE (recorded in
-- flyway_schema_history, can never re-fire) and is owner-gated (backup + review +
-- controlled apply). This runs BEFORE any Step-2 code — hence any HELPER — is live,
-- so a full rebuild-from-assignee_email is complete and safe.
--
-- After V49, task_assignee is authoritative and assignee_email becomes its derived
-- display mirror, maintained write-through by TaskAssignmentService (the sole
-- writer). This is the LAST time assignee_email is treated as source of truth.

-- 1. Drop every existing row. All are stale V48-backfill / drift; no HELPER can
--    exist yet (Step-2 write code isn't live until this deploy), so nothing of
--    value is lost. (Idempotent: a re-run — which Flyway prevents — would rebuild
--    to the same state.)
DELETE FROM task_assignee;

-- 2. Re-insert exactly one LEAD per currently-assigned group work order, from the
--    authoritative assignee_email (trimmed + lower-cased to match the writer's
--    normalization). A task with a blank/absent assignee_email gets no row.
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
  AND  btrim(t.assignee_email) <> '';
