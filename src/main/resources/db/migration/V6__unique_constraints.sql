-- Uniqueness invariants enforced at the database — audit P2-5 (DB-09 / DB-10 / S-5 / S-6).
--
-- Service-layer "find then save" patterns leave a race window where two concurrent
-- requests both observe "no row" and both insert. Pushing the invariant down to a
-- UNIQUE INDEX makes the second insert fail with DataIntegrityViolationException,
-- which the service layer catches and converts to idempotent success (return the
-- existing row). Partial indexes are used where the column is nullable so legacy
-- rows with NULL household_id remain valid (one row per owner).
--
-- Self-healing: each unique constraint is preceded by a deterministic DELETE that
-- collapses pre-existing duplicates to a single row. We keep the row with the most
-- "populated" data (highest non-zero column sum) and break ties by lowest id, so
-- the migration is idempotent and reproducible across environments — a duplicate
-- in a dev/staging snapshot won't refuse the migration; it'll be cleaned up.

-- ------------------------------------------------------------------
-- demographic: at most one row per (owner_email, household_id) pair.
--
-- Dedupe strategy: rank rows within each (LOWER(owner_email), household_id)
-- bucket by descending population score, ascending id; delete everything but rank 1.
-- ------------------------------------------------------------------
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
             PARTITION BY LOWER(owner_email), household_id
             ORDER BY (COALESCE(adults,0) + COALESCE(teens,0) + COALESCE(kids,0)
                       + COALESCE(infants,0) + COALESCE(dogs,0) + COALESCE(cats,0)
                       + COALESCE(pets,0)) DESC,
                      id ASC
           ) AS rn
    FROM demographic
    WHERE household_id IS NOT NULL
)
DELETE FROM demographic WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
             PARTITION BY LOWER(owner_email)
             ORDER BY (COALESCE(adults,0) + COALESCE(teens,0) + COALESCE(kids,0)
                       + COALESCE(infants,0) + COALESCE(dogs,0) + COALESCE(cats,0)
                       + COALESCE(pets,0)) DESC,
                      id ASC
           ) AS rn
    FROM demographic
    WHERE household_id IS NULL
)
DELETE FROM demographic WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

CREATE UNIQUE INDEX IF NOT EXISTS uk_demographic_owner_household
    ON demographic (LOWER(owner_email), household_id)
    WHERE household_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_demographic_owner_no_household
    ON demographic (LOWER(owner_email))
    WHERE household_id IS NULL;

-- ------------------------------------------------------------------
-- user_info: case-insensitive email uniqueness.
--
-- V1 baseline already declares `user_email VARCHAR(255) NOT NULL UNIQUE`, but
-- that's case-sensitive. UserInfoService.upsertByEmail normalizes to lowercase
-- on write, however a row written before normalization landed could differ only
-- in case from a new lowercase write. Dedupe by keeping the row with the most
-- recently-set fcm_token (proxy for "currently-active client"), tie-break by
-- ctid (Postgres physical row pointer) for determinism. Then add the
-- functional unique index.
--
-- Note: user_info's PK column is `user_id` (Firebase UID, varchar) — NOT a
-- numeric `id`. Using ctid for the rank id keeps this migration storage-engine
-- safe even if a future entity tweak adds a surrogate id column.
-- ------------------------------------------------------------------
WITH ranked AS (
    SELECT ctid,
           ROW_NUMBER() OVER (
             PARTITION BY LOWER(user_email)
             ORDER BY (fcm_token IS NOT NULL) DESC, ctid DESC
           ) AS rn
    FROM user_info
)
DELETE FROM user_info WHERE ctid IN (SELECT ctid FROM ranked WHERE rn > 1);

CREATE UNIQUE INDEX IF NOT EXISTS uk_userinfo_email_lower
    ON user_info (LOWER(user_email));

-- ------------------------------------------------------------------
-- group_challenge_progress: V1 baseline does NOT declare a PK (it was
-- generated structurally from the @Entity definitions, which omit the
-- composite key in their JPA mapping — Hibernate's @ElementCollection +
-- @MapKeyColumn doesn't surface a PK in the side-table CREATE). Prod's PK
-- on this table exists because earlier ddl-auto=update added it, but a
-- fresh DB seeded only from V1 has no enforced uniqueness — duplicate
-- (group_id, week_key) rows can survive.
--
-- Dedupe by ctid (any row wins — completed is a boolean, no preference
-- between duplicates) then add a PRIMARY KEY so future writes can't dupe.
-- IF NOT EXISTS isn't supported on ADD CONSTRAINT, so guard via a DO block
-- that checks pg_constraint.
-- ------------------------------------------------------------------
WITH ranked AS (
    SELECT ctid,
           ROW_NUMBER() OVER (
             PARTITION BY group_id, week_key
             ORDER BY (completed IS TRUE) DESC, ctid DESC
           ) AS rn
    FROM group_challenge_progress
)
DELETE FROM group_challenge_progress WHERE ctid IN (SELECT ctid FROM ranked WHERE rn > 1);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'group_challenge_progress_pkey'
          AND conrelid = 'group_challenge_progress'::regclass
    ) THEN
        ALTER TABLE group_challenge_progress
            ADD CONSTRAINT group_challenge_progress_pkey PRIMARY KEY (group_id, week_key);
    END IF;
END $$;
