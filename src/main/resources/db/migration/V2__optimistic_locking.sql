-- Adds @Version for optimistic locking — audit P1-6.
--
-- Targets entities that take concurrent writes (admin edits to Group,
-- profile edits to UserInfo, owner-driven activations of PlanActivation,
-- household admin toggles on HouseholdRitual). JPA increments the column
-- on every flush; any racing update against a stale read fails with
-- OptimisticLockingFailureException, which GlobalExceptionHandler
-- translates to HTTP 409 STALE_WRITE.
--
-- Existing rows pre-date the column, so each ALTER ships with a 0
-- default. Hibernate's ddl-auto=validate would reject a NULL column;
-- NOT NULL + DEFAULT 0 keeps validation green and gives every legacy
-- row a sane starting version.

ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE user_info
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE plan_activations
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE household_ritual
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
