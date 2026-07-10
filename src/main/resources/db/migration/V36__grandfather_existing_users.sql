-- V36 — Grandfather existing active users past the new FEMA onboarding wizard.
--
-- The onboarding gate (isOnboardingComplete) treats a null
-- onboarding_completed_at as "never onboarded" and routes the user into the
-- WelcomeWizard. Existing active users predate the expanded 5-step flow and
-- must NOT be abruptly forced back through it. Any user with prior activity
-- (last_active_at) but no completion stamp is backfilled to their last-active
-- time -- an honest "they were already using the app" marker rather than a
-- synthetic "now".
--
-- Safety:
--   * Data-only. No schema mutation, so spring.jpa.hibernate.ddl-auto=validate
--     is unaffected (nothing to re-validate against the entity mappings).
--   * Idempotent. The WHERE clause skips rows that already carry a stamp, so a
--     re-run (or a resumed partial run) is a no-op.
--   * Transactional. Plain UPDATE on a small table -- no CREATE INDEX
--     CONCURRENTLY, so it cannot hit the prod statement_timeout trap.

UPDATE user_info
   SET onboarding_completed_at = COALESCE(last_active_at, CURRENT_TIMESTAMP)
 WHERE onboarding_completed_at IS NULL
   AND last_active_at IS NOT NULL;
