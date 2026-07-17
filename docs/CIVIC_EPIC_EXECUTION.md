# Civic Agency Epic — Execution Tracker

Living tracker for the civic agency **operations** layer (the citizen-report →
agency-workflow chapter). The civic *primitive* is already live (see recon
below); this epic builds the operations on top of it. **Archive this file when
the epic completes.**

> Repos: **BE** = `sitprepapi 2` (branch `main`) · **FE** = `Status Now`
> (branch `sitprep-features`). BE is the enforcement boundary; FE mirrors it.

## Slice roadmap

| Slice | Scope | Migration? | Status |
|---|---|---|---|
| **1 — Agency pending queue (READ-ONLY)** | An authorized agency lists its civic reports by status, with location for display + map-ready coords. No claim/merge/dedupe. | No (index already exists) | **✅ SHIPPED** (see Slice 1 log) |
| **2 — Civic-claim + multi-agency** | Report tagged to ≥1 agency; an agency CLAIMS to work it + RELEASES for others in-jurisdiction. Claim gates operational actions. **Data-model change** (single `taggedAgencyGroupId` → multi-agency + claim/release). | Yes (hard-gated) | ⛔ NOT STARTED — needs the multi-agency data-model design pass (owner + coordinator). |
| **3 — Manual merge-duplicates** | Agency merges N reports into one canonical survivor; merged rows kept + linked "duplicate of". | Yes | ⛔ NOT STARTED |
| **4 — Create-time dedupe** | At submit, warn + suggest similar nearby reports (geo proximity + category). Never silent auto-merge. | No (geo primitives exist) | ⛔ NOT STARTED |

## Locked owner decisions (verbatim — do not lose)

1. **Dedupe proximity:** 0.5 miles default, category-dependent; the
   event/pothole **ADDRESS is tracked on the post**.
2. **Category match:** "similar" (or best industry practice), **not** exact-only.
3. **Time window:** start simple; let users dictate where things go — don't
   over-engineer a fixed window now.
4. **Dedupe action:** WARN + SUGGEST; leave merging to a human. **Never** silent
   auto-merge at create.
5. **Flagged resident:** show a preview of the similar report → "+1 / me too" if
   same, or create separate if not (ties into the existing `GhostDemandVote`
   distinct-resident counting).
6. **Agency:** any `Group` with `agencyAuthorized=true` (sufficient).
7. **Agency onboarding:** ONLY via the existing super-admin
   `VerificationApplication` pipeline.
8. **Multi-agency tagging:** a report can be tagged to MORE THAN ONE agency; an
   agency can CLAIM the report/task to complete, and RELEASE it for others to
   claim within the same jurisdiction. (Foundational to Slice 2 — a data-model
   change from single `taggedAgencyGroupId` to multi-agency + claim/release.
   **NOT built in Slice 1.**)
9. **Claim model:** civic is its own agency-gated path; the generic group-claim
   (`/posts/{id}/claim`) stays untouched (civic reports are `kind=civic-report`,
   never `kind=task`).
10. **Claiming GATES** operational actions (merge, spawn work orders, advance to
    scheduled/resolved) to the claiming agency; other tagged agencies see
    READ-ONLY until released. (Slice 2 model — coordinator's call.)
11. **Merge** keeps merged rows + links them "duplicate of" the survivor (never
    destroy citizen data).
12. A **merged report shows the CANONICAL report's status** so the original
    filer stays informed.
13. Other agencies see a **read-only** view; **merging carries any linked work
    orders onto the survivor**.

## Standing rules

- **Address handling REUSES the existing geocode/autopopulate backend API** —
  never reinvented. FE displays the real address or city/state.
  - The API is `GET /api/geocode/search` (forward type-ahead) + `GET
    /api/geocode/reverse?lat&lng` (coords → address), backed by
    `GeocodeService` / `NominatimGeocodeService` (`GeocodeResource`, unauth).
- **BE is the enforcement boundary; FE mirrors it.**

## Future phases (ROADMAP ONLY — do NOT start)

- **Work-order layer IN THE MAPS COMPONENT** — group tasks AND civic reports as
  filterable map markers by location. **Related** to the already-deferred
  map-unify pass (merging the groups explorer with the community map); scope the
  two TOGETHER when that phase comes, not bolted on separately. (Slice-1 queue
  DTO is deliberately map-ready — it exposes lat/lng — so this reuses it.)
- **Zip/jurisdiction onboarding enhancements** — being audited in a parallel
  research lane.

---

## Recon real-state (verified against code, carried from the recon pass)

Already live (the civic *primitive*):
- `kind="civic-report"` post kind — create-path validates category + requires a
  **verified** tagged agency, stamps `REPORTED`
  (`PostService.applyCommunityTypeFields`).
- `CivicCategory` (pothole/streetlight/debris/water/other) · `CivicStatus`
  (reported→acknowledged→scheduled→resolved, forward-only) — `constant/`.
- `Post.taggedAgencyGroupId` + `Post.civicStatus` + index
  `idx_task_tagged_agency (tagged_agency_group_id, civic_status)` (Flyway V11).
- `PATCH /api/posts/{id}/civic-status` — gated to the tagged agency's
  owner/admins, forward-only (`PostService.updateCivicStatus`).
- Civic-report → work-order link ("Slice H"): a `kind=task` may carry
  `sourcePostId` → a civic report, auto-acknowledging it
  (`PostService.applyWorkOrderSourceLink`).
- Agency = a `Group` with `agencyAuthorized=true` + jurisdiction geo
  (`jurisdictionLat/Lng/RadiusMiles`, cap 50mi) or legacy zips;
  `AgencyAuthorizationService`.
- Agency onboarding = super-admin `VerificationApplication` pipeline
  (`AgencyRequestService`). Ghost-tenant claim engine for unclaimed civic
  entities (`GhostTenantService`, `Group.claimState`).

**Address flag (foundational, noted — NOT built in Slice 1):** a civic report
stores `latitude`, `longitude`, `zipBucket`, and `placeLabel` (a Nextdoor-style
neighborhood→city→region→state short label from the reverse-geocode at create).
It does **NOT** store a formatted **street** address (work-order tasks keep
`addressStreet` inside `work_details`, but civic reports don't). Decision 1 says
"ADDRESS is tracked on the post" — for a street-level address the small
foundational item is to persist the FE-entered / reverse-geocoded formatted
address on the civic report at create (reusing `/api/geocode/reverse`). Until
then the queue displays `placeLabel`. **Owner/coordinator to confirm** whether
Slice 1's `placeLabel` display is enough for now or the street-address column
lands as a pre-Slice-2 foundational change.

---

## Slice 1 — Agency pending queue (READ-ONLY) — SHIPPED

**Delivers:** an authorized agency lists its own civic reports, filterable by
status, each row showing the report's real location (`placeLabel` city/state) +
map-ready coords. Read-only — no claim/merge/dedupe actions.

**BE (`sitprepapi 2`, `main`):**
- `PostRepo.findByTaggedAgencyGroupIdOrderByCreatedAtDesc(String)` — uses the
  existing `idx_task_tagged_agency` leading column; no new index, no migration.
- `AgencyAuthorizationService.requireAgencyAdmin(Group, callerEmail)` — read
  gate: caller ≥ admin/owner of an `agencyAuthorized` group (does NOT require
  jurisdiction geo, unlike `requireAgencyPostingAllowed`, since reading a queue
  needs no posting geo).
- `CivicQueueDto` (+ `CivicReportSummary`, `AgencyRef`, `CivicQueueCounts`) — the
  queue contract. **Designed to evolve:** `taggedAgencies` is a **List** from day
  one (today size 1) so Slice 2's multi-agency model changes only the population,
  not the contract. Exposes lat/lng (map-ready) + `placeLabel` for display.
- `PostService.listCivicReportsForAgency(groupId, statusWire, viewerEmail)` —
  one indexed query, computes per-status counts across all the agency's reports,
  filters `reports` by the optional status.
- `AgencyCivicResource` → `GET /api/agencies/{groupId}/civic-reports?status=`.

**FE (`Status Now`, `sitprep-features`):**
- `fetchAgencyCivicReports(groupId, status)` in `apiService.js`.
- `AgencyCivicQueuePage` at `/agencies/:groupId/civic-reports` — status tab bar
  with counts, report rows showing category · status · location · age. Read-only.
  Tokens only. (A MAP view is a deliberate future phase; the list is built now,
  DTO kept map-ready.)

**Status:** SHIPPED. See the commit SHAs / push confirmation appended by the
build.
