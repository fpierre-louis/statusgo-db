# System Traps & Patterns (backend)

Durable log of framework mechanisms that surprised us in `sitprepapi`. Append a
new `T-N` entry whenever a Spring / JPA / Flyway / Heroku / Postgres mechanism
bites in a non-obvious way. (The `Status Now` FE repo has its own companion
`docs/architecture/SYSTEM_TRAPS_AND_PATTERNS.md` for full-stack / React traps.)

---

### T-1 · A local BE boot can silently migrate PRODUCTION — a saved migration file needs no deploy to reach prod

**⚠ Highest-severity trap in the work-order effort: the failure is invisible and bypasses every deploy gate.**

**Symptom / context.** During Step 2 we carefully *gated* the V48 migration —
rehearsed it only on a throwaway local DB, never "ran it on prod." Yet when we
deployed an unrelated slug, Flyway logged:

```
Current version of schema "public": 48
WARN: Schema "public" has a version (48) that is newer than the latest available migration (47) !
```

Prod was **already at V48** and the `task_assignee` table already existed — even
though no deploy had ever carried V48.

**Mechanism.** `src/main/resources/application-local.yml` (the `local` Spring
profile) had its `datasource.url` pointed at the **production RDS** (a "run the
local FE against the prod BE" convenience), with **Flyway enabled**. Spring Boot
runs Flyway **on every application boot**. So the moment a developer's **local
BE booted or hot-reloaded** with a new migration file merely **saved on disk**,
Flyway connected to the *remote prod DB* and applied it. **No `git push`, no
Heroku release, no merge — just a file save + a local restart migrated prod.**
This bypasses the entire deploy pipeline and any human gate around it.

**Timeline (2026-07-14, the V48 incident).**
- `03:03:41` — committed `959ea6b` (V48 base migration file written to disk).
- `03:08:27` — **V48 applied to the prod RDS** — ~5 min later, via a local BE
  boot, *not* a deploy. (Recorded in prod `flyway_schema_history`, success=t,
  checksum `1677672374`.)
- `03:20:20` — committed `f6a48ae` (added an execution-time assertion to V48) —
  **12 min *after* prod had already run the pre-assertion version.** This later
  created a checksum-mismatch hazard: the branch file no longer matched what
  prod ran, so a future slug carrying V48 would fail Flyway `validate` on boot.
  (Fixed by reverting the file to the applied bytes — see `d6f8e68`.)

**Collateral damage this caused.**
- The V48 "execution gate" was moot before we ever reached it — prod already had
  the schema.
- Backups taken "before the deploy" were **post-V48** — no clean pre-migration
  restore point existed.
- The branch migration file drifted from the applied version → checksum-mismatch
  boot-failure risk on the next real deploy.

**Fix (applied 2026-07-14).** In `application-local.yml`:
1. **Repoint `datasource.url` to `localhost`** (never a remote DB).
2. **`spring.flyway.enabled: false`** for the `local` profile — the load-bearing
   guard: with Flyway off, a local boot runs **zero** migrations regardless of
   what the URL points at, so it can never mutate a remote schema even if the URL
   is wrong again.

**Standing rules.**
- **A local/dev profile's datasource NEVER points at a remote (prod/staging) DB.**
  If you must read prod from your laptop, do it read-only via `heroku pg:psql`,
  not by pointing the app's datasource at it.
- **Local Flyway stays OFF** (`spring.flyway.enabled: false` in `application-local.yml`).
  Migrate a local DB manually (the replay loop is in the file header). Migrations
  reach prod **only** through a Heroku release, never through a laptop.
- **Migrations are immutable once applied.** If a migration has run *anywhere*
  real, never edit its file — Flyway checksums it. Reconcile by reverting the
  file to the applied bytes (keeps `flyway_schema_history` honest), not by
  `flyway repair` (which rewrites history to match a file that never ran).
- When Flyway logs `schema version (N) newer than latest available migration`,
  **stop and investigate** — it means the DB was migrated out-of-band.

---

### T-2 · CI cannot verify the `task_assignee` partial-unique / one-LEAD invariant — H2 can't express a partial index

**Symptom / context.** Step 2 makes `task_assignee` the authority for work-order
roles, with a hard **"≤ 1 LEAD per task"** rule enforced by a Postgres
**partial-unique index**:

```sql
CREATE UNIQUE INDEX uk_task_assignee_one_lead
    ON task_assignee (task_id) WHERE role = 'LEAD';   -- V48
```

plus a `CHECK (role IN ('LEAD','HELPER'))`. **No automated test exercises either
constraint.**

**Mechanism.** The test profile disables Flyway and uses H2 with
`ddl-auto=create-drop`, so the schema tests run against is generated from the JPA
entity mappings — **not** from the V48 SQL. H2 has no notion of a **partial
(filtered) unique index** (`WHERE role='LEAD'`), and the JPA `@Table`/annotations
can't express one either, so `uk_task_assignee_one_lead` and the role `CHECK`
simply **don't exist** in the test database. A test could insert two `LEAD` rows
for one task and H2 would happily accept them — green build, broken invariant.

**What actually guards the invariant.**
- **At runtime:** only real **Postgres** enforces the partial-unique index.
- **In code:** `TaskAssignmentService.setLead` demotes any existing Lead to
  HELPER and **flushes that demote BEFORE writing the new LEAD**, so the index is
  never transiently violated. This *ordering* is unit-tested at the mock level
  (`TaskAssignmentServiceTest` via Mockito `InOrder`) — but the mock has no index,
  so the test proves the **call order**, not that Postgres accepts the result.
- **Before Step 2 shipped:** the index + backfill were validated **once, by hand,
  against a throwaway real Postgres** (the V48/V49 rehearsal).

**Standing rule.** A green `mvn clean test` does **NOT** prove the one-LEAD
invariant. **Anyone who touches `setLead`, the demote-before-promote ordering, the
`task_assignee` schema, or V48/V49 must RE-REHEARSE against a real Postgres**
(not H2) before shipping — confirm two LEAD rows are rejected and the role CHECK
holds. If you want this in CI, it needs a Testcontainers Postgres profile; until
then it is rehearsal-verified only. (Related: T-1 on migrations reaching prod.)

---

## Template for new entries

### T-N · <one-line trap name>

**Symptom / context:** …
**Mechanism:** …
**Fix / standing rule:** …
