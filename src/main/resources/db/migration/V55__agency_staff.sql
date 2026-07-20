-- V55 — Agency Staff relationship (separate join table, not a role-enum value).
--
-- Expresses "this person is STAFF of this agency" — the non-admin employee who
-- works the agency's queue but must NOT be able to change agency settings. Owner
-- decision (docs/lanes/AGENCY_STAFF_PHASE0_DESIGN.md): a dedicated additive join
-- table, NOT a fourth OWNER/ADMIN/MEMBER/NONE role. Staff is INDEPENDENT of group
-- role — a city employee is frequently also a resident who joined the same group,
-- and a role forces one-or-the-other while a table lets both be true (D-d).
--
-- Design notes (mirror V48__task_assignee_roles):
--   * PURELY ADDITIVE — one new table only. Touches ZERO existing columns, alters
--     ZERO existing tables, backfills NOTHING (owners/admins are NOT written here;
--     the eligibility predicate is "staff OR admin" — D-c). Rollback is a clean
--     `DROP TABLE agency_staff` in a later migration, with no data loss.
--   * Plain transactional DDL on a new/empty table — NO CREATE INDEX CONCURRENTLY,
--     so it cannot hit the prod statement_timeout trap
--     (reference_flyway_concurrently_timeout).
--   * The matching entity (domain/AgencyStaff.java) + repo ship with this
--     migration so ddl-auto=validate stays satisfied at boot. The FK to
--     groups(group_id) is Postgres-only (not a JPA annotation); the H2 test
--     profile builds the table from the entity via ddl-auto=create-drop and does
--     NOT exercise it — it is validated by the V55 rehearsal against real Postgres.
--   * Identity is EMAIL (user_email), matching group_member_emails / group_admin_emails
--     / group_mute_pref / group_read_state / task_assignee. An email may name a person
--     who has not registered yet (an agency can add crew before they install), so
--     there is deliberately NO FK from user_email to user_info. Per-user cleanup on
--     account deletion is app-level in AccountDeletionService (Phase 1 code).
--   * Deletion: group_id -> groups(group_id) ON DELETE CASCADE (D-b). A staff row is
--     meaningless without its agency and — being a dedicated @Entity, not an
--     @ElementCollection on Group — sits OUTSIDE JPA's Group-cascade graph, so the
--     DB-level cascade is what guarantees cleanup on any delete path. This is the
--     task_assignee "assignees die with their task" precedent (V48), NOT the
--     ON-DELETE-SET-NULL "detach" precedent (V51 project self-FK), which does not
--     apply — a staff row has nothing to survive as once its agency is gone.

-- ---------------------------------------------------------------------------
-- 1. The join table
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agency_staff (
    id          BIGSERIAL     PRIMARY KEY,
    -- FK to the agency group. CASCADE: staff rows die with their agency.
    group_id    VARCHAR(255)  NOT NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    -- Stored already lower-cased + trimmed by the writer, so the UNIQUE below is
    -- effectively case-insensitive without a functional index. No FK to user_info.
    user_email  VARCHAR(255)  NOT NULL,
    -- Verified caller who granted staff (accountability parity with task_assignee).
    added_by    VARCHAR(255),
    added_at    TIMESTAMP     NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 2. Constraints + indexes
-- ---------------------------------------------------------------------------
-- One staff row per (person, agency). Makes "add" idempotent AND is exactly what
-- lets a person be staff at MULTIPLE agencies (N rows, one per group).
CREATE UNIQUE INDEX IF NOT EXISTS uk_agency_staff_user_group
    ON agency_staff (user_email, group_id);

-- Lookup: "is this user staff anywhere / where?" — the /api/me batch-load
-- (matches idx_gmp_user / idx_grs_user / idx_task_assignee_email).
CREATE INDEX IF NOT EXISTS idx_agency_staff_user
    ON agency_staff (user_email);

-- Lookup: "list this agency's staff" + the ON DELETE CASCADE delete path
-- (Postgres does NOT auto-index FK columns, so this is required).
CREATE INDEX IF NOT EXISTS idx_agency_staff_group
    ON agency_staff (group_id);
