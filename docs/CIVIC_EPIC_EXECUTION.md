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
| **2 — Civic-claim + multi-agency** | Report tagged to ≥1 agency; an agency CLAIMS to work it + RELEASES for others in-jurisdiction. Claim gates operational actions. **Data-model change** (single `taggedAgencyGroupId` → multi-agency + claim/release). | Yes (V53) | 🟡 **BUILT LOCALLY — AWAITING PROD GATE.** Code + V53 committed on `main` (local only, unpushed). Local Postgres apply + `ddl-auto=validate` green; `CivicAgencyServiceTest` 7/7; full suite green except the pre-existing GhostTenant flake. Backfill is INSERT-ONLY; local count 0 (dev DB has no tagged civic reports) — **prod count measured at rehearsal**. Prod apply pending the backup → rehearse → owner review → apply → verify gate (see Slice 2 build log below). |
| **3 — Manual merge-duplicates** | Agency merges N reports into one canonical survivor; merged rows kept + linked "duplicate of". | Yes | ⛔ NOT STARTED |
| **4 — Create-time dedupe** | At submit, warn + suggest similar nearby reports (geo proximity + category). Never silent auto-merge. | No (geo primitives exist) | ⛔ NOT STARTED |

## Locked owner decisions (verbatim — do not lose)

1. **Dedupe proximity:** 0.5 miles default, category-dependent; the
   event/pothole **ADDRESS is tracked on the post**. ✅ **SATISFIED** by the
   Address Foundation pass (below) — a full street address now persists on every
   new civic report (`work_details.addressStreet`) + its lat/lng.
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

**Address flag — RESOLVED by the Address Foundation pass (below).** (Historical:
Slice 1 shipped with only `placeLabel` display because civic reports stored
lat/lng + zipBucket + placeLabel but no street address. The owner then decided a
full street address must be captured before Slice 2 / dedupe.)

---

## Address Foundation — lands before Slice 2 — SHIPPED

**Owner decision:** civic reports must carry a **full street address** + a
tappable "get directions" link. Captured now (before dedupe) so it isn't
backfilled once real reports exist.

**Scope determination: CODE-ONLY — NO MIGRATION.** The street address persists
in the civic report's existing `work_details` JSONB bag under `addressStreet` —
the SAME structured field work-order tasks already use — so there is **no new
column and no migration** (no gate needed). `work_details` (V47) is already
bound from the request body, preserved through create
(`sanitizeWorkDetails`), and `deriveLifeSafety` only escalates on true
life-safety flags, so an address-only bag is inert.

**Geocode API reused (not reinvented):** BE `GET /api/geocode/search` (forward)
+ `GET /api/geocode/reverse` (reverse) via `GeocodeService`/`NominatimGeocodeService`;
FE helpers `geocodeSearch` + `geocodeReverse` in `sitprepApiService.js`
(both return `{ label, lat, lng }`). (Note: the FE also has a Google-Places
`useAddressAutocomplete` — deliberately NOT used here; the owner directive is the
Nominatim proxy.)

**BE (`sitprepapi 2`, `main`):**
- `CivicQueueDto.CivicReportSummary` gains `formattedAddress` (read from
  `work_details.addressStreet`; null on legacy reports). `PostService.toCivicSummary`
  populates it. Create already persists the FE-sent `work_details.addressStreet`
  (no create-path change). `placeLabel` stays as the legacy display fallback.

**FE (`Status Now`, `sitprep-features`):**
- `CivicFields` — new "Where is it?" issue-location capture: an address
  type-ahead over `geocodeSearch` + a one-tap "Use my current location" over
  `geocodeReverse`. Sets `{ address, lat, lng }` (the ISSUE's point).
- `PostComposer` — a civic report now sends the issue's lat/lng (overriding the
  reporter's live spot) + `work_details.addressStreet`; a location is required to
  submit (drives directions + future dedupe).
- `shared/utils/mapsLink.js` — `directionsUrl(lat,lng)`: Apple Maps universal
  link on iOS, Google Maps elsewhere (external native-maps deep link, NOT the
  deferred in-app map layer).
- Display: the agency queue row + the community civic card (`PostBody`) show the
  real street address (fallback `placeLabel`) + a "Directions" link.

**Live verification:** the `work_details.addressStreet` round-trip is already
proven live (work-order tasks store/read it today); the queue DTO mapping is
deterministic. A full civic-report round-trip on prod needs a verified-publisher
agency (privileged, admin-only), so that path is covered by the existing
mechanism + a post-deploy Slice-1 queue regression E2E.

**Status:** SHIPPED (code-only; see commit SHAs / deploy note).

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
