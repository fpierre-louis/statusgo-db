-- V100 — household roster composition + "claim your spot" (2026-10-07).
--
-- Contract: SitPrep FE docs/epics/household_roster_claim/EXEC-B-roster-claim.md.
--
-- Three things, one release:
--
--   1. household_manual_member.band — the age band a manual member is counted
--      in (ADULT | TEEN | KID | INFANT). Until now the band was RE-DERIVED from
--      is_adult/age on every surface, so a person named from a "Teen · Add a
--      name" row with no age typed was stored as a kid and the plan's teen slot
--      never filled. Nullable on purpose: an old dyno still serving during the
--      deploy window inserts without it, and the read path derives the band the
--      same way the backfill below does (HouseholdBand.derive).
--
--   2. household_member_band — the band an ACCOUNT holds in a household. An
--      account is ADULT by default (the ToS 18+ attestation is the legal gate),
--      so this table only matters when an account claimed a manual spot whose
--      band was not ADULT: the slot keeps its band. PK (household_id,
--      user_email); one row per membership.
--
--   3. household_claim_invite — single-use, expiring token bound to one manual
--      member. Separate from group_invites on purpose: a claim token is
--      consumed-not-counted, names a person rather than a group, and must never
--      be redeemable through the generic household-invite redeem path or show
--      up in the admin "Manage invites" list. manual_member_id carries no FK:
--      the manual member is deleted by the claim, and the token row must
--      survive it so a re-accept by the same account stays idempotent.
--
-- Then a ONE-TIME repair: raise every household demographic that counts fewer
-- people/pets in a band than the household has named in it (accounts are
-- ADULT here — household_member_band is empty at this point). Raise only,
-- never lower. Idempotent: a second run changes nothing.
--
-- Plain transactional DDL (no CONCURRENTLY — prod RDS times out). Verified on
-- a scratch local Postgres with every migration replayed (EXEC-B).

-- 1 ── manual member band ───────────────────────────────────────────────────
ALTER TABLE household_manual_member
    ADD COLUMN IF NOT EXISTS band VARCHAR(8);

ALTER TABLE household_manual_member
    DROP CONSTRAINT IF EXISTS ck_manual_member_band;
ALTER TABLE household_manual_member
    ADD CONSTRAINT ck_manual_member_band
        CHECK (band IS NULL OR band IN ('ADULT', 'TEEN', 'KID', 'INFANT'));

-- Same rule as HouseholdBand.derive (and the FE's former manualBand):
-- is_adult → ADULT; else 0 < age < 2 → INFANT; age >= 13 → TEEN; else KID.
UPDATE household_manual_member
   SET band = CASE
                WHEN is_adult THEN 'ADULT'
                WHEN age IS NOT NULL AND age > 0 AND age < 2 THEN 'INFANT'
                WHEN age IS NOT NULL AND age >= 13 THEN 'TEEN'
                ELSE 'KID'
              END
 WHERE band IS NULL;

-- 2 ── account band per membership ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS household_member_band (
    household_id VARCHAR(255) NOT NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    user_email   VARCHAR(320) NOT NULL,
    band         VARCHAR(8)   NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_household_member_band PRIMARY KEY (household_id, user_email),
    CONSTRAINT ck_hmb_band CHECK (band IN ('ADULT', 'TEEN', 'KID', 'INFANT')),
    CONSTRAINT ck_hmb_email_lower CHECK (user_email = lower(user_email))
);

-- 3 ── claim tokens ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS household_claim_invite (
    token             VARCHAR(64)  PRIMARY KEY,
    household_id      VARCHAR(255) NOT NULL REFERENCES groups (group_id) ON DELETE CASCADE,
    manual_member_id  VARCHAR(64)  NOT NULL,
    issued_by_email   VARCHAR(320) NOT NULL,
    issued_at         TIMESTAMPTZ  NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    consumed_at       TIMESTAMPTZ,
    consumed_by_email VARCHAR(320),
    revoked_at        TIMESTAMPTZ,
    CONSTRAINT ck_hci_consumed_pair CHECK ((consumed_at IS NULL) = (consumed_by_email IS NULL)),
    CONSTRAINT ck_hci_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_hci_not_both CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);

-- At most one LIVE (unconsumed, unrevoked) token per manual member. Expired
-- live rows are revoked by the service before a new one is minted. H2 cannot
-- express a partial index; the service enforces it with find-then-insert and
-- this index is the race backstop on Postgres.
CREATE UNIQUE INDEX IF NOT EXISTS uk_hci_live_member
    ON household_claim_invite (manual_member_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

-- Per-household mint rate limit (count since now() - 24h).
CREATE INDEX IF NOT EXISTS idx_hci_household_issued
    ON household_claim_invite (household_id, issued_at);

-- 4 ── one-time repair: counts never below named ────────────────────────────
WITH hh AS (
    SELECT g.group_id
      FROM groups g
     WHERE lower(g.group_type) = 'household'
),
accounts AS (
    SELECT e.group_id, count(DISTINCT lower(trim(e.member_email))) AS n
      FROM group_member_emails e
      JOIN hh ON hh.group_id = e.group_id
     WHERE e.member_email IS NOT NULL AND trim(e.member_email) <> ''
     GROUP BY e.group_id
),
manual AS (
    SELECT m.household_id,
           count(*) FILTER (WHERE m.band = 'ADULT')  AS adults,
           count(*) FILTER (WHERE m.band = 'TEEN')   AS teens,
           count(*) FILTER (WHERE m.band = 'KID')    AS kids,
           count(*) FILTER (WHERE m.band = 'INFANT') AS infants
      FROM household_manual_member m
     GROUP BY m.household_id
),
pets AS (
    SELECT p.household_id,
           count(*) FILTER (WHERE lower(trim(coalesce(p.species, ''))) = 'dog') AS dogs,
           count(*) FILTER (WHERE lower(trim(coalesce(p.species, ''))) = 'cat') AS cats,
           count(*) FILTER (WHERE lower(trim(coalesce(p.species, ''))) NOT IN ('dog', 'cat')) AS others
      FROM household_pet p
     GROUP BY p.household_id
),
named AS (
    SELECT hh.group_id AS household_id,
           coalesce(a.n, 0) + coalesce(m.adults, 0) AS adults,
           coalesce(m.teens, 0)   AS teens,
           coalesce(m.kids, 0)    AS kids,
           coalesce(m.infants, 0) AS infants,
           coalesce(p.dogs, 0)    AS dogs,
           coalesce(p.cats, 0)    AS cats,
           coalesce(p.others, 0)  AS others
      FROM hh
      LEFT JOIN accounts a ON a.group_id = hh.group_id
      LEFT JOIN manual m   ON m.household_id = hh.group_id
      LEFT JOIN pets p     ON p.household_id = hh.group_id
)
UPDATE demographic d
   SET adults  = GREATEST(d.adults,  n.adults),
       teens   = GREATEST(d.teens,   n.teens),
       kids    = GREATEST(d.kids,    n.kids),
       infants = GREATEST(d.infants, n.infants),
       dogs    = GREATEST(d.dogs,    n.dogs),
       cats    = GREATEST(d.cats,    n.cats),
       pets    = GREATEST(d.pets,    n.others)
  FROM named n
 WHERE d.household_id = n.household_id
   AND (d.adults < n.adults OR d.teens < n.teens OR d.kids < n.kids
        OR d.infants < n.infants OR d.dogs < n.dogs OR d.cats < n.cats
        OR d.pets < n.others);
