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

### T-3 · V49 is SINGLE-USE — never rebuild `task_assignee` from `assignee_email` again (it would wipe Helpers)

**Applied to prod 2026-07-14 (release v504, `flyway_schema_history` v49 `success=t`).** After this
migration, the authority direction is **permanently reversed** and must never be run backwards.

**The rule.** `V49__reconcile_task_assignee.sql` did a one-time rebuild: `DELETE FROM task_assignee`
then re-`INSERT` one `LEAD` per assigned `kind='task'` row **from `assignee_email`**. That was correct
**only** because it ran *before any Step-2 write code — hence any HELPER — was live**, so
`assignee_email` (which only ever knows the single Lead) was a complete source. **That window is now
closed.** From v504 on:

- **`task_assignee` is THE authority** for assignment membership + roles (`LEAD`/`HELPER`), written
  write-through by `TaskAssignmentService` (the sole writer). `assignee_email` is a **derived display
  mirror** (Lead ?? earliest Helper ?? null), re-derived FROM the collection.
- **NEVER regenerate `task_assignee` from `assignee_email` again.** The mirror can represent at most
  one person, so a rebuild would **silently DELETE every HELPER** (and any non-primary assignee) —
  destroying real assignment data with no error.

**What enforces it / what doesn't.** Flyway's run-once semantics stop *V49 itself* from re-firing
(it's recorded in `flyway_schema_history` and can never re-run). The real hazard is a **human**
copy-pasting V49's body into `psql` "to clean up drift" or "re-sync the mirror," or writing a V50 that
does the same rebuild. **Don't.** If `task_assignee` ever needs repair after Step 2, repair it **as
the authority** — fix the offending rows directly — and let `TaskAssignmentService.rederiveMirror`
push the corrected value back into `assignee_email`. Reconciliation only ever flows
collection → mirror now, never mirror → collection. (Related: T-1 immutable-once-applied migrations;
T-2 the one-LEAD invariant is Postgres-enforced, not CI-tested.)

---

### T-4 · Adding a `PostKind` wire value REQUIRES widening the `chk_task_kind` CHECK in the same migration

**Symptom / context:** V51 added `PostKind.PROJECT` ("project") but not the DB CHECK. Everything green —
`mvn test` 205/205, a clean local boot, AND a full prod-clone rehearsal — yet `INSERT … kind='project'`
on prod threw `new row … violates check constraint "chk_task_kind"`. Caught only by the live post-deploy
smoke (a rolled-back insert); backfilled by V52.

**Mechanism:** `chk_task_kind` (added V9, widened V11) enumerates the allowed `kind` values as a DB CHECK.
The H2 test profile builds its schema from JPA entities (`ddl-auto=create-drop`), which do NOT carry this
CHECK (it lives only in migration SQL) — so unit tests insert ANY kind and pass (see T-2). The Postgres
rehearsal applied the migration + proved additive invariants but never inserted a `kind='project'` row, so
it didn't exercise the CHECK either. `kind` has THREE sources of truth that must agree: the entity/`PostKind`
enum, the service-layer `AUTHORIZED_KINDS` validation, and this DB CHECK — and only the first two are
compiler/test-visible.

**Fix / standing rule:** Whenever you add a value to `PostKind`, ship a migration that widens the CHECK in
the SAME release: `ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_kind; ALTER TABLE task ADD CONSTRAINT
chk_task_kind CHECK (kind IS NULL OR kind IN (…full list…, '<new>'))`. Copy the value list verbatim from the
LIVE prod constraint (`SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='chk_task_kind'`) so
no existing kind is dropped (dropping one would make the ADD fail on the first offending existing row). The
only pre-FE check that catches a miss is a post-deploy smoke that actually INSERTs the new kind inside a
rolled-back transaction — run it. (Related: T-2 H2 can't express DB constraints; T-1 migrations are
immutable once applied.)

---

## Template for new entries

### T-N · <one-line trap name>

**Symptom / context:** …
**Mechanism:** …
**Fix / standing rule:** …
