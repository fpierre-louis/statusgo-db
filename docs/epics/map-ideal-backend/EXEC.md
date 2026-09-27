# EXEC — Map Ideal backend (EXEC 3½ BE units)

**Owner approval:** 2026-09-27 ("create the BE schema or logic needed to allow
the core features that don't have data feeding … like whether someone is at
school or work. We will eventually implement the app on watches.")

**Wire contract (authoritative):** `Status Now/docs/epics/map_and_slate/EXEC-3.5-backend.md`
(frontend repo — read-only from here). Background: `Status Now/docs/epics/map_and_slate_audit.md` §6,
`docs/audits/2026-09-26-map-ideal-contract.md`.

**Branch:** `main` — auto-deploys on push. **Commit only. Never push. Never deploy.**

## Scope

| # | Unit | Migration |
|---|---|---|
| BE-1 | Security: `profileImageUrl` host allow-list at write; `@Transactional` onto `nudgeMember` | — |
| BE-2 | Location `source` + `accuracyM`; place presence (`atPlace`); `lastSeenNear` label | V83 |
| BE-3 | `MemberSummary.phone`, household scope + household-member viewer only | — |
| BE-4 | `MemberSummary.inAlertIds` (polygon / zone match, cache-only zone lookups) | — |
| BE-5 | `MapPlaceDto.tier/deploy`; `MapPoiDto.priorityReason/createdAt/authorDisplayName/canSendAreaAlerts`; asks on the map | — |
| BE-6 | `resource_listing.hours_json` + server-computed `openNow/closesAt/opensAt` | V84 |
| BE-7 | "Still here?" `map_confirmation` + `POST /api/map/confirmations` + read counts | V85 |

## Out of scope

- Aid quantity (description verbatim; never parse a number out of prose).
- Agency↔area matching ("posts alerts for this area" stays unbacked).
- Zone-geometry endpoint.
- Push copy changes (safety copy is under a signed review).
- Removing `phone` from `/api/userinfo/email/{email}` (follow-up once the FE stops reading it).
- Any push / deploy / prod migration run.

## Binding rules carried from the brief

- Privacy gate: the `LocationSharing` gate that nulls `lastKnownLat/Lng` nulls EVERY new
  member-location field (`atPlace`, `lastSeenNear`, `locationSource`, `locationAccuracyM`,
  `inAlertIds`). Opt-out and never-fixed stay indistinguishable (locked 2026-07-02). No
  "reason" field.
- Never invent data: null when the record does not hold it. `authorDisplayName` is a name or
  null (never an email / local-part). `openNow` is null without hours.
- Migrations: next free numbers after V82; plain transactional DDL; no `CONCURRENTLY`.
- `./mvnw -q package` green before every commit; explicit pathspecs only.

## Checklist

### BE-0 · baseline
- [x] Baseline `./mvnw -q package` green before any edit — *verified by: EXIT=0, 63 s, 2026-09-27*
- [x] Highest migration is V82 → this epic uses V83/V84/V85 — *verified by: `ls db/migration | sort -V` and local `flyway_schema_history` max = 82*

### BE-1 · security
- [x] `ProfileImageUrlPolicy`: https only, no userinfo/odd port, host on allow-list, ≤255 chars; blank → clear — *verified by: `ProfileImageUrlPolicyTest` (25 cases: 8 allowed hosts, 15 refusals incl. suffix/userinfo/port/r2.dev/http tricks, blank, >255)*
- [x] `updateUserById` (PUT) rejects a changed, non-allow-listed value with 400; an unchanged legacy value passes untouched — *verified by: `UserInfoProfileImageWriteTest.putRejects…/putEchoing…/putStores…`; 400 via the resource's existing `@ExceptionHandler(IllegalArgumentException)`*
- [x] `patchUserById` (PATCH) rejects a non-allow-listed value with 400 (outside the reflection try/catch that swallows errors) — *verified by: `UserInfoProfileImageWriteTest.patchRejects…` (throws, stored value untouched), `patchRejectsANonString`*
- [x] Sign-in upserts (`applyPatch`) never store a non-allow-listed value (dropped, not 400 — see Deviations) — *verified by: `UserInfoProfileImageWriteTest.upsertDrops…` / `upsertStoresAProviderPhoto`*
- [x] `@Transactional` moved from the `NudgeResult` record onto `nudgeMember` — *verified by: `UserInfoProfileImageWriteTest.nudgeMemberIsTheTransactionalElement` (reflection: on the method, absent from the record)*
- [x] Tests: allow-list matrix, PUT/PATCH/upsert behaviour, annotation placement — *verified by: the two test classes above, 34 cases green*
- [x] `./mvnw -q package` green; commit — *verified by: EXIT=0, 986 tests / 0 failures (one pre-existing fixture, `UserInfoServiceUpsertByFirebaseUidTest`, moved from `cdn.example.com` to the CDN host — its subject is retry-idempotency, not the host)*

### BE-2 · source, accuracy, place presence, last-seen (V83)
- [ ] V83: `user_info` (+source, accuracy, current place id/since, last-seen label + anchor), `user_saved_location` (+kind, share_presence, radius_m), `live_location_points` (+source)
- [ ] V83 rehearsed on a throwaway local Postgres DB cloned schema-only from `statusnowdb`
- [ ] `PATCH /api/userinfo/me/location` accepts `{lat,lng,source?,accuracyM?}`; unknown source → null; accuracy clamp
- [ ] Live-location points accept optional `source`
- [ ] Saved places: `kind`, `sharePresence` (default false), `radiusM` (default 150, clamp 50–2000) on read + create/update
- [ ] Match rule on every location write (both write paths); `since` preserved while the place is unchanged
- [ ] `lastSeenNear` recomputed from `Place.shortLabel()` on the ~2 mi throttle
- [ ] `MemberSummary.atPlace/lastSeenNear/locationSource/locationAccuracyM` — all under the existing gate
- [ ] New `UserInfo` columns never serialize on the raw entity and cannot be written by the reflection PATCH
- [ ] Tests: radius + accuracy, since-preservation, privacy gate nulls every new field
- [ ] `./mvnw -q package` green; commit

### BE-3 · phone
- [ ] `MemberSummary.phone` only for a Household view whose viewer is owner/admin/member of it
- [ ] Tests: household member sees it; group view, platform-admin/staff viewer, missing account → null
- [ ] `./mvnw -q package` green; commit

### BE-4 · inAlertIds
- [ ] Same `id` as `/api/alerts/feed` cards (`NormalizedAlert.id()`), active alerts only
- [ ] Zone lookups cache-only on the roster read; misses queue a background warm; the location write warms
- [ ] `[]` when located and in none; null when withheld/absent
- [ ] Tests: polygon containment, zone match, gate nulls it, no NWS call on read
- [ ] `./mvnw -q package` green; commit

### BE-5 · map DTOs
- [ ] `MapPlaceDto.tier` (meetingTier verbatim) + `deploy`
- [ ] `MapPoiDto.priorityReason` (`poster-urgent` iff URGENT), `createdAt`, `authorDisplayName`, `canSendAreaAlerts`
- [ ] Asks on the map — ruling check at `MapDiscoveryService.java:66`
- [ ] Tests
- [ ] `./mvnw -q package` green; commit

### BE-6 · resource hours (V84)
- [ ] V84 `resource_listing.hours_json jsonb NULL` (rehearsed locally)
- [ ] Validation (IANA tz, 0–3 ranges/day, HH:mm, midnight crossing) → 400 on malformed
- [ ] `ResourceListingDto.hours/openNow/closesAt/opensAt`; `openNow` null without hours
- [ ] Tests incl. DST + midnight crossing
- [ ] `./mvnw -q package` green; commit

### BE-7 · "Still here?" (V85)
- [ ] V85 `map_confirmation` + unique `(target_type, target_id, user_email)` (rehearsed locally)
- [ ] `POST /api/map/confirmations` → 200 `{count,lastAt,mine:true}`; 429 inside 10 min; authenticated
- [ ] `confirmations: {count,lastAt} | null` on `ResourceListingDto` + `MapPoiDto` (distinct users, 7 days)
- [ ] Separate from `post_confirm` ("Me too")
- [ ] Tests incl. cooldown
- [ ] `./mvnw -q package` green; commit

## Deviations / narrowings

- **BE-1 · sign-in upserts drop instead of 400.** The contract names the full update and the
  patch; both 400. The two sign-in upserts (`POST /api/userinfo`, `/api/userinfo/firebase`) also
  write the avatar via `applyPatch`, and leaving them open would bypass the allow-list entirely.
  They DROP a disallowed value (keeping the stored one) instead of refusing, because a 400 there
  locks a person out of the app if a provider moves its avatar CDN. Stricter than the contract,
  never looser.
- **BE-1 · `api.dicebear.com` is on the allow-list.** Not a sign-in provider, but the frontend
  avatar builder (`AvatarPhotoModal.jsx:44`) writes it — refusing it would break a shipped
  feature. `*.r2.dev` is deliberately NOT on it (shared Cloudflare dev domain = any customer's
  bucket).
- **Finding, not fixed (out of scope):** `PATCH /api/userinfo/{id}` is a reflective setter over
  every `UserInfo` field except `id`/`userEmail`. A user can PATCH their own
  `verifiedPublisher`, `verifiedPublisherEmergencyPostingEnabled`, `subscription*`, etc. BE-2 adds
  its own new server-derived fields to a deny-list; the general hole needs an owner decision
  (allow-list the editable fields).

## Watch client contract

(Written with BE-2.)
