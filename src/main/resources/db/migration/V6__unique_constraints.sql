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
-- in case from a new lowercase write. Dedupe to MAX(id) (most recent) then add
-- the functional unique index.
-- ------------------------------------------------------------------
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
             PARTITION BY LOWER(user_email)
             ORDER BY id DESC
           ) AS rn
    FROM user_info
)
DELETE FROM user_info WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

CREATE UNIQUE INDEX IF NOT EXISTS uk_userinfo_email_lower
    ON user_info (LOWER(user_email));

-- ------------------------------------------------------------------
-- group_challenge_progress: V1 baseline already declares PRIMARY KEY (group_id, week_key)
-- so duplicates cannot exist by definition. The originally-planned UNIQUE INDEX
-- would be redundant with the PK — skip. Left here as documentation that this
-- invariant IS enforced, just by the PK rather than a named index.
-- ------------------------------------------------------------------
