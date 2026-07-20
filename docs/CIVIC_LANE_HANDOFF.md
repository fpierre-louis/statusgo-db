# Civic Lane — Handoff / State

Resume-instantly snapshot of the civic agency epic. **Every claim below was
verified against the live repos + prod on 2026-07-19**, not recited from memory.
Where the execution tracker drifted from reality, **the live repo wins** — the
drift is called out in §1.

Companion docs: `docs/CIVIC_EPIC_EXECUTION.md` (per-slice log + the 13 locked
owner decisions + Gate-C watch item + lessons). This doc is the fast index.

---

## 1. VERIFIED STATE (checked, not assumed)

### BE — `sitprepapi 2`, branch `main`
- **origin/main:** `00fe9cd` (`fix(civic): honor civicAgencyIds on create + expose multi-agency on the feed`) — this is what prod runs.
- **local HEAD:** `1389986` — **one UNPUSHED commit** ahead: `docs(civic): mark Slice 2 COMPLETE (BE+FE+18/18) + test-blind-spot lesson` (doc-only; deliberately not pushed to avoid a redeploy for a doc — rides the next BE push).
- **Working tree:** only `src/main/resources/application-local.yml.example` (M) + `application-local.yml.bak` (untracked) — **intentionally unstaged, NOT mine** (local DB config; never commit).
- Verify: `git fetch origin && git rev-parse origin/main HEAD && git log --oneline origin/main..HEAD && git status --short`

### FE — `Status Now`, branch `sitprep-features`
- **origin/sitprep-features == local HEAD:** `51bfce07d` (`feat(civic): render multi-agency + claim state on the community civic card`). **In sync, nothing unpushed.**
- Working tree: unrelated other-lane files dirty (e.g. `docs/AGENCY_EXPERIENCE_*.md`, ask/nav edits) — **other lanes are active; commit civic files by explicit pathspec only.**
- Verify: `git fetch origin && git rev-parse origin/sitprep-features HEAD && git log --oneline origin/sitprep-features..HEAD`

### Prod (Heroku `statusgo-db`)
- **Release: v514** ("Deploy 00fe9cd4" == origin/main). `heroku releases -a statusgo-db -n 1`
- **Flyway schema: V53** (latest migration on disk is V53; no unshipped migration). `heroku pg:psql -a statusgo-db -c "SELECT max(version::int) FROM flyway_schema_history WHERE success;"`
- **Health:** `GET /api/agencies/x/civic-reports` (unauth) → **401** (not 500). `curl -s -o /dev/null -w "%{http_code}" https://statusgo-db-0889387bb209.herokuapp.com/api/agencies/x/civic-reports`
- FE Heroku (`statusnow`) is a **separate manual remote** — GitHub push does NOT deploy the FE.

### ⚠ DISCREPANCY (execution doc drifted)
`docs/CIVIC_EPIC_EXECUTION.md` on **origin/main still shows Slice 2 as "SHIPPED v513"**, NOT the v514 completion — the "Slice 2 COMPLETE (BE+FE+18/18)" update is only in the **unpushed local commit `1389986`**. `git show origin/main:docs/CIVIC_EPIC_EXECUTION.md | grep -c "COMPLETE — BE + FE"` → 0; local → 1. **Pushing `1389986` reconciles it.**

---

## 2. WHAT'S SHIPPED (all live on prod v514)

### Slice 1 — agency civic queue (read-only) — LIVE
- **What:** an authorized agency lists the civic reports tagged to it, filterable by `CivicStatus`.
- **BE:** `GET /api/agencies/{groupId}/civic-reports?status=` → `AgencyCivicResource.java`; `PostService.listCivicReportsForAgency`; gated by `AgencyAuthorizationService.requireAgencyAdmin` (admin of an `agencyAuthorized` group).
- **FE:** `src/groups/agency/AgencyCivicQueuePage.jsx`, route `/agencies/:groupId/civic-reports` (App.js). Helper `fetchAgencyCivicReports`.

### Slice 2 — multi-agency tagging + claim/release — LIVE, verified 18/18
- **Migration V53** (`db/migration/V53__civic_multi_agency.sql`) added: `civic_report_agency` join (post_id→task ON DELETE CASCADE, `tag_source`, `active` tombstone, per-tag `claimed`/`claimed_at`/`claimed_by_email`/`released_at`) + `idx_cra_post` + `idx_cra_agency_claimed` + `uk_cra_post_agency` + partial-unique `uk_civic_report_one_claim` (one active claim/report); `task.claiming_agency_group_id` (single-claim mirror); `civic_coverage_gap` (zip-keyed orphan ledger). Backfill was INSERT-only, 0 rows on prod (no pre-existing tagged reports). Gated-applied as v513; **backup `b007`** retained.
- **BE model:** `service/CivicAgencyService.java` (auto-derive at create, orphan hook, claim/release, gating, queue tag fold); `domain/CivicReportAgency.java` + `CivicCoverageGap.java`; `repo/CivicReportAgencyRepo.java` + `CivicCoverageGapRepo.java`; `Post.java` gained `claiming_agency_group_id` + `@Transient civicAgencyIds`.
- **Endpoints:** `POST /api/agencies/{groupId}/civic-reports/{postId}/claim` + `/release` (`AgencyCivicResource`); `PATCH /api/posts/{id}/civic-status` gating in `PostService.updateCivicStatus`; work-order spawn gate in `PostService.applyWorkOrderSourceLink`.
- **Gaps fixed (v514, `00fe9cd`):** (1) `create()` now copies `civicAgencyIds` onto its working `Post` (`applyCommunityTypeFields` civic branch) — the deselect was inert before; (2) `CommunityExtras` exposes the multi-agency fields (see §4).
- **FE:** queue claim/release + multi-agency + gated status actions (`AgencyCivicQueuePage.jsx`); composer D2 auto-derive confirm/adjust (`src/community/composer/CivicFields.jsx`, `src/community/PostComposer.js`); community card multi-agency (`src/community/PostBody.jsx` `CivicStatusRow`, flattened in `src/community/CommunityFeed.js`); helpers `claimCivicReport`/`releaseCivicReport`/`patchCivicStatus`/`fetchAgencies({lat,lng})` in `src/shared/api/apiService.js`.

### Address foundation — LIVE (code-only, no dedicated column)
- Civic reports carry a full street address in the shared `work_details.addressStreet` bag (same field work-order tasks use) + lat/lng + full `postcode`. Captured FE-side via the geocode proxy; exposed as `CivicQueueDto.formattedAddress` + a "Directions" native-maps deep link (`src/shared/utils/mapsLink.js`). The create path captures the full `postcode` in `PostService.create`'s reverse-geocode block (feeds the resolver's zip side).

### Jurisdiction resolver — LANDED + WIRED
- `service/AgencyJurisdictionService.agenciesCovering(lat, lng, zip)` → covering `agencyAuthorized` agencies, **radius ∪ claimed-zip** (overlap preserved; city AND county both returned).
- **All 3 consumers wired (verified):**
  - (a) **life-safety alert recipients** — `AgencyAuthorizationService.recipients` now UNIONs radius + zip (dedup by email); broadens, never narrows.
  - (b) **community co-sign** — `PostService.localAgencyForViewer` → `agenciesCovering` (PostService.java:1597).
  - (c) **civic tag-picker** — `PostService.listAgencies(lat,lng,zip)` → `agenciesCovering` (PostService.java:1543); `GET /api/agencies?lat&lng&zip`.
- None left unwired.

---

## 3. WHAT'S QUEUED (not started)

- **Slice A — worker "My Work" view** (FE-only; prompt exists). Why: a claiming agency's staff need a personal list of what they're working, not just the group queue.
- **Slice 3 — merge duplicates.** All **13 owner decisions locked** (see `CIVIC_EPIC_EXECUTION.md`); a full design proposal exists (self-column `merged_into_post_id`, read-through canonical status, union tags, claim-gated `POST …/{canonicalId}/merge`, orphan/citizen-view rules). **Not started.**
- **Slice B — agency-aware admin home** (HELD, waiting on the design-unification lane's mode architecture).
- **Slice 4 — create-time dedupe** (warn + suggest at submit; geo proximity + category). **Not designed.**
- **Parked:** `/ws/info` 403 diagnostic (unrelated STOMP handshake probe).

---

## 4. THE LIVE CONTRACT (deployed v514, verified)

**Civic queue** — `GET /api/agencies/{groupId}/civic-reports?status=` → `CivicQueueDto`:
- `counts { reported, acknowledged, scheduled, resolved, total }`
- `reports[]` = `CivicReportSummary { id, category, status, title, description, latitude, longitude, placeLabel, formattedAddress, requesterEmail, createdAt, acknowledgedAt, scheduledFor, resolvedAt, claimState, claimingAgencyGroupId, taggedAgencies[] }`
- `AgencyRef { groupId, name, claimed, tagSource }` — `tagSource` ∈ `auto | citizen_added | legacy | merged`.
- `claimState` ∈ `unclaimed | claimed` (released → unclaimed, decision 4).

**Claim / release** — `POST /api/agencies/{groupId}/civic-reports/{postId}/claim` | `/release` → `{ postId, claimState, claimingAgencyGroupId }`. Claim 409 if already claimed; 403 if not entitled. Release restricted to the claiming agency.

**Civic-status advance** — `PATCH /api/posts/{id}/civic-status { status, note }`. ACKNOWLEDGE open to any active-tagged agency; SCHEDULE/RESOLVE (+ work-order spawn, + future merge) require the CLAIMING agency (403 otherwise). Forward-only.

**Create-path `civicAgencyIds`** — `POST /api/posts { kind:"civic-report", civicCategory, latitude, longitude, civicAgencyIds:[...] }`:
- absent/null → auto-derive ALL covering agencies (resolver).
- present → the filer's confirmed set: covered+kept → `active auto`; covered+deselected → `active=false` tombstone; added (must be `agencyAuthorized`) → `citizen_added`; non-authorized ids dropped.
- empty covering → orphan: report still persists (`civicStatus=reported`), coverage-gap signal recorded.

**Community feed DTO** — `PostDto.community` (`CommunityExtras`) for a civic post now carries (all `@JsonInclude(NON_NULL)`, so **non-civic posts omit them**): `taggedAgencies[]` (same `AgencyRef` shape), `claimState`, `claimingAgencyGroupId`. The legacy single `taggedAgency { id, name, verified, note }` is **still populated** (keep-then-retire, decision 7).

**Resolver** — `AgencyJurisdictionService.agenciesCovering(Double lat, Double lng, String zip)` → `List<Group>` (agencyAuthorized only; radius ∪ zip; deduped by groupId; zip matches ordered first). Tolerates null lat/lng (skip radius) or null/blank zip (skip zip). Tag-picker endpoint: `GET /api/agencies?lat&lng&zip` → `AgencyDto[]`.

---

## 5. TRAPS / LESSONS (carry forward)

- **T-4 — three sources of truth for `kind`** (entity default, service `PostKind` validation, DB `chk_task_kind` CHECK) must stay in lockstep. H2 tests **cannot see the Postgres CHECK** — only a live insert-smoke catches a mismatch. *Avoid: when adding a PostKind wire value, widen the CHECK in the same migration; smoke-insert on a clone.*
- **Test blind spot** — a test that sets a field on the SAME object it passes in cannot catch a missing field-copy across the request boundary. Slice 2's `CivicAgencyServiceTest` did this and missed that `create()` dropped `civicAgencyIds`; the live FE E2E caught it. *Avoid: build the payload the way the real request path does — a SEPARATE `incoming` → `create()` → assert the PERSISTED result. See `CivicCreateTagsCopyTest` (fails without the copy).*
- **Column-name reuse** — `parent_task_id` was already taken by the repost/quote feature; V51 bundles used a NEW `project_id`, and V53 claims used a NEW `merged_into_post_id`-style column. *Avoid: grep the schema for a column name before reusing it.*
- **No `CREATE INDEX CONCURRENTLY` on prod RDS** — it hits `statement_timeout`, gets cancelled, and leaves an INVALID index. *Avoid: plain transactional `CREATE INDEX` on small tables (V53 did this).*
- **Local Flyway stays localhost-only** — never point the `local` profile at prod (it auto-migrates; this leaked V48 to prod once). *Avoid: rehearse on a restored throwaway DB; override the URL via env var to a localhost DB, never edit the yml to a remote.*
- **Gate-C life-safety broadening** (see execution doc watch item) — the alert recipients now union radius ∪ zip. Delta is 0 today (the sole authorized agency has geo, no zips), but the broadening is live. *Avoid a surprise: the moment ANY agency gets a jurisdiction zip list, re-measure its recipient set before its first real alert.*
- **`mvn package` runs the full test suite** which Heroku also runs on deploy; a broken/flaky test fails the release. `GhostTenantFeatureTest` has a **known full-suite-ordering flake** (passes 12/12 in isolation) — if it appears, re-run it isolated before assuming a regression.
- **Prod `task` count drifts** from live traffic — snapshot immediately before/after an apply, don't compare to an older number.

---

## 6. HOW TO RESUME

1. **First action (housekeeping):** push the pending BE doc commit so the tracker matches reality —
   `cd "sitprepapi 2" && git push origin main` (pushes `1389986`; it's doc-only, auto-deploys an identical build — or bundle it with the next real BE change to avoid a redeploy; **owner OK required before any push**).
2. **Then, the next slice** is the owner/coordinator's call among §3. The two "ready to build" options:
   - **Slice A (worker "My Work" view)** — FE-only, prompt exists, smallest.
   - **Slice 3 (merge duplicates)** — decisions locked, design proposal ready, needs a V54-style additive migration (self-column + index; no backfill) with the full gate.
   Do NOT start either without the go-ahead.

_Last verified: 2026-07-19, prod v514 / Flyway V53._
