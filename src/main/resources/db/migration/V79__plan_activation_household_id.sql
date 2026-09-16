-- V79 — an activation names the household it belongs to.
--
-- WHY: PlanActivation is keyed on the LAUNCHER'S EMAIL and nothing else, so
-- "this activation's household" has only ever been an inference. Two readers
-- inferred it differently:
--
--   * MeService.resolveActiveActivationIdForHome scans the VIEWER'S base
--     household member set, and
--   * PlanActivationService.canEnd asks only whether caller and owner share
--     ANY household.
--
-- That gap is why /deployedplan's End button could not be pointed at the
-- household-wide route: the page has no household id, and the viewer's base
-- household is the wrong answer for anyone who belongs to two. It also leaves
-- the 2026-05-22 ruling — plans are keyed by household_id, not ownerEmail —
-- unadopted by the one table where it matters most during an emergency.
--
-- NULLABLE ON PURPOSE. A launcher with no base household is a real state (a
-- brand-new account that activated before joining a household), and a NOT NULL
-- column would make that a 500 at the worst possible moment. Readers treat
-- NULL as "fall back to the ownerEmail scan", which is exactly today's
-- behaviour — so this column can only ever narrow a query, never break one.

ALTER TABLE plan_activations
    ADD COLUMN IF NOT EXISTS household_id varchar(64);

-- Backfill from the launcher's pinned base household, which is the same value
-- createActivation now stamps going forward. Measured against prod before
-- writing this: 2 rows, both ended, both resolving to one household, 0 left
-- NULL. Cheap now; it will not stay cheap.
UPDATE plan_activations a
   SET household_id = u.base_household_id
  FROM user_info u
 WHERE LOWER(u.user_email) = LOWER(a.owner_email)
   AND u.base_household_id IS NOT NULL
   AND a.household_id IS NULL;

-- The read this exists to serve: "every live activation in this household".
-- Partial, because a query for live rows is the only one that uses it and the
-- ended rows are the ones that accumulate forever.
--
-- PLAIN, NOT CONCURRENTLY: a CONCURRENTLY build times out on this RDS and
-- leaves an INVALID index behind (trap logged 2026-07-04). The table has 2
-- rows.
CREATE INDEX IF NOT EXISTS idx_plan_activations_household_live
    ON plan_activations (household_id, activated_at DESC)
 WHERE ended_at IS NULL;
