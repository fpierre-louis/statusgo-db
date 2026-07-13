-- V45 (renumbered from V43 to sit after the already-applied V44) — Unified Task / Work Order schema (Phase 1 of the Task/Work-Order overhaul).
--
-- Ports the legacy disaster-relief intake schema (recovered from the deleted
-- src/shared/tasks/Request/* tree at commit 258adaf26 — hazard triage +
-- liability release) into the modern Post entity (@Table name = "task"), adds
-- the DB-level liability gate, and seeds the multi-tenant "GHOST" claim state
-- for unclaimed civic entities.
--
-- Design notes:
--   * ADDITIVE + idempotent (ADD COLUMN IF NOT EXISTS) — no drops, no renames.
--     The `task` table name is preserved (renaming it broke boot before; see
--     PostKind Javadoc + SYSTEM_TRAPS_AND_PATTERNS.md).
--   * Plain transactional DDL on small tables — no CREATE INDEX CONCURRENTLY,
--     so it cannot hit the prod statement_timeout trap
--     (reference_flyway_concurrently_timeout).
--   * Matching entity fields (Post.java / Group.java) ship with this migration
--     so ddl-auto=validate stays satisfied at boot.
--   * The state-machine enum grows from 5 to 9 values; the widened status
--     column (16 -> 32) is required because "VERIFICATION_PENDING" (20 chars)
--     and "LIABILITY_PENDING" (17) overflow the old length(16) column.

-- ---------------------------------------------------------------------------
-- 1. Legacy triage / hazard columns (from legacy WorkInfoForm.js)
-- ---------------------------------------------------------------------------
ALTER TABLE task ADD COLUMN IF NOT EXISTS near_power_lines  BOOLEAN     NOT NULL DEFAULT false;
ALTER TABLE task ADD COLUMN IF NOT EXISTS electrical_hazard BOOLEAN     NOT NULL DEFAULT false;
ALTER TABLE task ADD COLUMN IF NOT EXISTS water_level       VARCHAR(32);
-- Nullable tri-state: NULL = unknown, TRUE = safe to enter, FALSE = not safe
-- (preserves the legacy yes/no/unknown semantics of safeToEnter).
ALTER TABLE task ADD COLUMN IF NOT EXISTS safe_to_enter     BOOLEAN;

-- ---------------------------------------------------------------------------
-- 2. Liability / release columns (from legacy ReleaseForm.js)
-- ---------------------------------------------------------------------------
-- Whether this work order requires a signed liability waiver before it may be
-- actioned. Default false so every existing row (and all personal preparedness
-- tasks) is ungated and unaffected by the CHECK below.
ALTER TABLE task ADD COLUMN IF NOT EXISTS liability_required       BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE task ADD COLUMN IF NOT EXISTS release_signed           BOOLEAN NOT NULL DEFAULT false;
-- SHA-256 (hex) of the exact waiver copy the requester agreed to — tamper-
-- evident proof-of-terms without inventing a PKI.
ALTER TABLE task ADD COLUMN IF NOT EXISTS release_text_hash        VARCHAR(64);
-- The legacy "requester did not sign" escape hatch: a required free-text reason
-- (not present / refused / language barrier). A non-null value here satisfies
-- the liability gate WITHOUT a signature, matching field practice.
ALTER TABLE task ADD COLUMN IF NOT EXISTS release_exception_reason VARCHAR(500);

-- ---------------------------------------------------------------------------
-- 3. Widen the status column for the expanded state machine
-- ---------------------------------------------------------------------------
-- 5 -> 9 states: adds DRAFT, LIABILITY_PENDING, VERIFICATION_PENDING, CLOSED.
-- Stored as the enum name (EnumType.STRING); no Postgres enum type / value
-- CHECK exists, so widening the varchar is all that's needed at the DB layer.
ALTER TABLE task ALTER COLUMN status TYPE VARCHAR(32);

-- ---------------------------------------------------------------------------
-- 4. THE LIABILITY GATE — unbypassable DB-level constraint
-- ---------------------------------------------------------------------------
-- A liability-required task that is neither signed nor formally excepted MUST
-- NOT rest in any operational/terminal state. This makes the illegal state
-- unrepresentable regardless of which service (or raw SQL) writes the row —
-- the mandate's "database-level constraint", not just an app guard.
--
-- DROP-then-ADD keeps the file idempotent if replayed against a throwaway DB.
ALTER TABLE task DROP CONSTRAINT IF EXISTS ck_task_liability_gate;
ALTER TABLE task ADD CONSTRAINT ck_task_liability_gate CHECK (
    NOT (
        liability_required = true
        AND release_signed = false
        AND release_exception_reason IS NULL
        AND status IN ('IN_PROGRESS', 'VERIFICATION_PENDING', 'CLOSED', 'DONE')
    )
);

-- ---------------------------------------------------------------------------
-- 5. Multi-tenant GHOST claim state (unclaimed civic entities)
-- ---------------------------------------------------------------------------
-- claim_state lifecycle: CLAIMED (default, a normal owned group) -> GHOST
-- (a resident filed against a city not yet on the platform) -> INVITED
-- (a single consent-gated claim invite was sent) -> CLAIMED (provisioned via
-- the existing VerificationApplication pipeline). See DOCS_GROWTH_MONETIZATION.md.
ALTER TABLE groups ADD COLUMN IF NOT EXISTS claim_state         VARCHAR(16) NOT NULL DEFAULT 'CLAIMED';
-- Aggregate, NON-PII demand counter — how many distinct residents are waiting
-- on this (still unclaimed) civic target. Drives whether a claim invite fires.
ALTER TABLE groups ADD COLUMN IF NOT EXISTS ghost_demand_signal INTEGER     NOT NULL DEFAULT 0;
