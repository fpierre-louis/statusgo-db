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
- [x] V83: `user_info` (+source, accuracy, current place id/since, last-seen label + anchor), `user_saved_location` (+kind, share_presence, radius_m), `live_location_points` (+source) — *verified by: `V83__location_source_and_place_presence.sql`; every new `@Column` has its DDL (compared field-by-field)*
- [x] V83 rehearsed on a throwaway local Postgres DB cloned schema-only from `statusnowdb` — *verified by: `psql -1 -f` applied clean, re-applied clean (idempotent), CHECK rejected `kind='gym'`*
- [x] `PATCH /api/userinfo/me/location` accepts `{lat,lng,source?,accuracyM?}`; unknown source → null; accuracy clamp — *verified by: `LocationPingFrameTest` (watch fix + lat/lng-only older client), `LocationPresenceServiceTest.sourceIsOneOfThreeOrNull/accuracyIsAPositiveClampedInt`*
- [x] Live-location points accept optional `source` — *verified by: `LiveLocationServiceTest.updatePointRecordsTheSourceOnThePointAndTheUser` / `…UnknownSourceAsNull`*
- [x] Saved places: `kind`, `sharePresence` (default false), `radiusM` (default 150, clamp 50–2000) on read + create/update — *verified by: `SavedPlacePresenceFieldsTest` (create, defaults, bad kind → IAE/400, partial update, JSON wire keys both DTOs)*
- [x] Match rule on every location write (both write paths); `since` preserved while the place is unchanged — *verified by: `LocationPresenceServiceTest.insideTheRadiusMatches/accuracyWidensTheMatchByAtMost100m/onlyOptedInPlacesMatchAndTheNearestWins/sinceIsTheArrivalTimeAndSurvivesLaterPings`; both writers now go through `applyFix` (grep: no other `setLastKnownLat` in main)*
- [x] `lastSeenNear` recomputed from `Place.shortLabel()` on the ~2 mi throttle — *verified by: `LocationPresenceServiceTest.labelIsResolvedOnce…/aLabelThatNoLongerDescribesTheFixIsCleared` (throttle measured from the label's anchor — see Deviations)*
- [x] `MemberSummary.atPlace/lastSeenNear/locationSource/locationAccuracyM` — all under the existing gate — *verified by: `RosterLocationPrivacyGateTest` (gate nulls all 7 location fields; opted-out and never-fixed JSON identical minus identity; places behind the gate never queried; sharePresence flipped off / foreign place id → no atPlace)*
- [x] New `UserInfo` columns never serialize on the raw entity and cannot be written by the reflection PATCH — *verified by: `SavedPlacePresenceFieldsTest.theDerivedUserInfoFieldsNeverSerializeRaw/theReflectivePatchCannotSetThem`*
- [x] Tests: radius + accuracy, since-preservation, privacy gate nulls every new field — *verified by: 5 new/extended test classes above, all green*
- [x] `./mvnw -q package` green; commit — *verified by: EXIT=0, 1015 tests / 0 failures. `UserSavedLocationWriteDtoTest.noMassAssignment` pins the client-settable component list; extended with the three contract fields (still no owner/id/server-derived field)*

### BE-3 · phone
- [x] `MemberSummary.phone` only for a Household view whose viewer is owner/admin/member of it — *verified by: `GroupViewService.assemble` computes `includePhones = isHousehold && GroupRole.fromGroup(g, viewer) != NONE`; `RosterPhoneScopeTest.aHouseholdMemberSeesHouseholdPhones`*
- [x] Tests: household member sees it; group view, platform-admin/staff viewer, missing account → null — *verified by: `RosterPhoneScopeTest` (3 tests; blank phone → null too). Agency staff take the same non-member branch as the platform admin (`GroupRole.NONE`), so the platform-admin case covers it*
- [x] `./mvnw -q package` green; commit — *verified by: EXIT=0, 1018 tests / 0 failures*

### BE-4 · inAlertIds
- [x] Same `id` as `/api/alerts/feed` cards (`NormalizedAlert.id()`), active alerts only — *verified by: `MemberAlertAreaServiceTest.idsAreTheFeedCardIdsForTheSamePoint` (builds the real `AlertFeedService.feedFor` for the same point and snapshot; every inAlertIds entry is a feed card id); `expiredCancelledAndPointAlertsHaveNoInside`*
- [x] Zone lookups cache-only on the roster read; misses queue a background warm; the location write warms — *verified by: `unknownZonesAreNullNotEmptyAndNeverANetworkCall` (warmPoint called, `zoneCodesForPoint` never); `NwsZoneService.warmPoint` is de-duplicated per key, ≤200 pending, single warm thread; the presence ping calls `warmAlertZones`*
- [x] `[]` when located and in none; null when withheld/absent — *verified by: `emptyListWhenLocatedAndInsideNothing`; `RosterLocationPrivacyGateTest` (gate closed → null; opted-out ≡ never-fixed still holds; idsFor called only for visible members). Also null while the point's zones are unknown — see Deviations*
- [x] Tests: polygon containment, zone match, gate nulls it, no NWS call on read — *verified by: `GeoJsonAreaTest` (hole, non-convex, multipolygon, non-area), `MemberAlertAreaServiceTest` (6), `RosterLocationPrivacyGateTest` (7)*
- [x] `./mvnw -q package` green; commit — *verified by: EXIT=0, 1028 tests / 0 failures*

### BE-5 · map DTOs
- [x] `MapPlaceDto.tier` (meetingTier verbatim) + `deploy` — *verified by: `MapDtoEnrichmentTest.mapPlacesCarryTheirOwnTierAndDeployVerbatim` (meeting place tier+deploy, evac plan deploy only, home neither)*
- [x] `MapPoiDto.priorityReason` (`poster-urgent` iff URGENT), `createdAt`, `authorDisplayName`, `canSendAreaAlerts` — *verified by: `MapDtoEnrichmentTest` (HIGH ≠ URGENT; name/group-name/null, never contains `@`; capability true/false for agencies, null otherwise); legacy 22-arg constructor keeps Overpass/activation call sites unchanged; `cachedPoiPayloadsRoundTrip…` proves Jackson still binds the canonical constructor and pre-BE-5 cached payloads load*
- [x] Asks on the map — ruling check at `MapDiscoveryService.java:66` — *verified by: line 65-66 reads only `// Community Post kinds that count as mutual aid on the map.` — no ruling; `git log -L` shows it unchanged since the file was created (b1e236a); no ruling in the FE epic docs. `ask` added for signed-in viewers (see Deviations); `asksAndNamesAreForSignedInViewersOnly`*
- [x] Tests — *verified by: `MapDtoEnrichmentTest` (6)*
- [x] `./mvnw -q package` green; commit — *verified by: EXIT=0, 1034 tests / 0 failures*

### BE-6 · resource hours (V84)
- [x] V84 `resource_listing.hours_json jsonb NULL` (rehearsed locally) — *verified by: applied + re-applied on the schema clone; CHECK rejects a non-object (`'[1]'`); `MapIdealPersistenceTest.resourceHoursRoundTripAsJsonb` (real Hibernate JSON mapping on H2)*
- [x] Validation (IANA tz, 0–3 ranges/day, HH:mm, midnight crossing) → 400 on malformed — *verified by: `OpeningHoursTest.refusesMalformedSchedules` (13 cases), `ResourceListingHoursTest.submitStores…/theSubmitterSetsAndClearsHours` (400 via ResponseStatusException)*
- [x] `ResourceListingDto.hours/openNow/closesAt/opensAt`; `openNow` null without hours — *verified by: `ResourceListingHoursTest.noHoursMeansNoOpenState/theReadIsComputedAtReadTime/aStoredScheduleThatNoLongerParsesReportsNothing`*
- [x] Tests incl. DST + midnight crossing — *verified by: `OpeningHoursTest` spring-forward (range spanning the gap; start inside the skipped hour), fall-back (repeated hour counted once), Fri 18:00–02:00 open Sat 01:00, adjacent 20:00–24:00 + 00:00–03:00 merge, 24/7 has no close*
- [x] `./mvnw -q package` green; commit — *verified by: EXIT=0, 1052 tests / 0 failures*

### BE-7 · "Still here?" (V85)
- [x] V85 `map_confirmation` + unique `(target_type, target_id, user_email)` (rehearsed locally) — *verified by: applied + re-applied on the schema clone; duplicate (type,id,user) rejected by the unique index; `target_type='group'` rejected by the CHECK*
- [x] `POST /api/map/confirmations` → 200 `{count,lastAt,mine:true}`; 429 inside 10 min; authenticated — *verified by: `MapConfirmationServiceTest.theEndpointAnswers200Then429WithRetryAfter/theEndpointRequiresSignIn` (401), `aReconfirmInsideTenMinutesIsRefused` (retry 360 s), `MapIdealPersistenceTest.aPersonIsOneRowAndTheCooldownHoldsAgainstTheRealTable` (H2)*
- [x] `confirmations: {count,lastAt} | null` on `ResourceListingDto` + `MapPoiDto` (distinct users, 7 days) — *verified by: `MapIdealPersistenceTest.confirmationSummariesCountDistinctPeopleInTheLastSevenDays` (real JPQL on H2: 8-day-old row excluded, unconfirmed target absent → null); one batched query per target type per response*
- [x] Separate from `post_confirm` ("Me too") — *verified by: own table/entity/repo/service; no read or write of `post_confirm` anywhere in the diff (`git diff --stat` shows no PostConfirm file touched)*
- [x] Tests incl. cooldown — *verified by: `MapConfirmationServiceTest` (7), `MapIdealPersistenceTest` (4)*
- [x] `./mvnw -q package` green; commit — *verified by: EXIT=0, 1061 tests / 0 failures*

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
- **BE-2 · two anchor columns beyond the contract** (`user_info.last_seen_near_lat/lng`). The
  contract says "the same ~2 mi throttle the zip already uses". That throttle compares against
  the PREVIOUS fix, so someone moving in small steps never crosses 2 mi between two pings and
  the label would describe a place they left miles ago. The label's throttle is measured from
  where it was resolved; a label that no longer describes the fix and cannot be re-resolved is
  cleared, not kept. Not on the wire.
- **BE-2 · one reverse geocode now serves zip + label, on both write paths.** The zip keeps its
  rule, but it is also refreshed when the label refresh fires, and live-location points (which
  used to move `lastKnownLat/Lng` without touching the zip) now refresh it too. Strictly fresher;
  the zip drives agency geo-alert targeting.
- **BE-2 · `current_place_id` is not a foreign key.** `user_info` is saved from a stale in-memory
  copy on every ping; `ON DELETE SET NULL` would turn a place deleted mid-ping into a 500. The
  read path re-checks existence, ownership and `share_presence` instead.
- **BE-2 · an unknown saved-place `kind` is a 400.** The contract is silent; unlike `source`
  (device-originated, must stay lenient), `kind` comes from a fixed picker, so a stray value is a
  client defect (same call as `ResourceCategory`).
- **BE-2 · the roster WS frame carries the four fields too.** `MemberLocationFrame`
  (`/topic/group/{id}/members/location`) gained `atPlace/lastSeenNear/locationSource/
  locationAccuracyM`, appended; it is only published to groups whose gate is open, so it rides
  the same gate. Live-location frames/DTOs are unchanged (the contract asks only that points
  ACCEPT `source`).
- **BE-4 · "zone codes include … OR polygon contains" narrowed to geometry-first.** For an alert
  WITH a polygon, only containment counts; zones are used only for alerts without geometry. A
  storm-based warning's UGC lists every county it touches, so OR-ing zones would put a whole
  county "inside" a polygon a few miles wide. Same tier order as the feed's own `matchTypeFor`.
  Point geometries (quakes) and alerts with neither polygon nor UGC (FEMA) are never "inside".
- **BE-4 · `inAlertIds` is also null while the point's NWS zones are unknown** (not yet cached —
  e.g. after a dyno restart; a background lookup is queued and the next read answers). The
  contract lists null only for withheld/absent location; answering `[]` here would state "inside
  nothing" about a point nobody looked up — the false calm the audit warns about.
- **BE-4 · "active"** = the feed's own filter (not `AlertSafetyPolicy` SUPPRESS, which already
  drops expired/all-clear) plus lifecycle `active|updated` at read time.
- **BE-5 · asks (and `authorDisplayName`) only for signed-in viewers.** No ruling excluded asks
  (the kind set simply predates them), so they are added — but `GET /api/community/map` is
  `permitAll` for guest browsing, while the community feed that already shows an ask with the
  asker's name and place is authenticated. Putting "a person needs help here", with a name, on an
  anonymous crawlable endpoint would widen exposure beyond anything the owner approved. A
  signed-in viewer sees on the map exactly what they already see in the feed.
- **BE-5 · `authorDisplayName` is "First Last"** (the same name the authenticated feed already
  shows), or the group's name for a post authored as a group; the "Frank D." abbreviation is the
  frontend's call. `createdAt` is also filled for group pins (the group's own `createdAt`), null
  for external POIs.
- **BE-6 · no "resource MapPoiDto" exists, so hours ship on `ResourceListingDto` only.** Resource
  listings never become `MapPoiDto` (the map converts them client-side); the POI families are
  groups, aid posts, Overpass amenities and activation places. Adding always-null hours fields to
  `MapPoiDto` would be noise; OSM `opening_hours` parsing is a separate grammar and out of scope.
- **BE-6 · the update path is new:** there was no resource update endpoint at all, so "create/update
  accept hours" got `PATCH /api/resources/{id}` `{"hours": {...} | null}` — submitter only (403
  otherwise; OFFICIAL/imported rows have no submitter and change through their seeder), 404 when
  missing, 400 when malformed or when the body has no `hours` key.
- **BE-6 · shape details the contract left open:** `"24:00"` is allowed as an END ("until
  midnight"); equal start and end is refused (0 h or 24 h? — use `["00:00","24:00"]`); unknown
  top-level fields are refused; `note` ≤ 280 chars; a missing day is closed. A schedule with NO
  ranges on any day (e.g. note-only "By appointment") yields `openNow: null` — it is not a
  schedule, so it makes no open/closed claim. Only the relevant boundary is set: open →
  `closesAt` (null when open around the clock for the next week), closed → `opensAt`.
- **BE-6 · a stored schedule is re-validated on read;** one that no longer parses reports
  `hours: null, openNow: null` rather than a guess.
- **BE-7 · wire details the contract left open.** `targetId` is the bare id: `"42"` (resource),
  `"123"` (community post = `MapPoiDto.postId`), `"node/123"` (OSM = `MapPoiDto.id` minus
  `"overpass:"`). The 429 body repeats the current `{count, lastAt, mine: true}` plus
  `retryAfterSeconds`, and sets `Retry-After`. 400 = unknown type / malformed id; 404 = a resource
  that is not APPROVED, or a post that is missing or not community-scope (404, not 403, so group
  post ids cannot be probed). OSM ids are validated by form only — OSM places live in the
  Overpass tile cache, not a table. `confirmations` is attached to aid-post and OSM POIs only
  (groups/agencies/activation places are not "still here" targets). Two simultaneous first
  confirms by one person: the unique index keeps one, the other gets a bare 429.

## Watch client contract

For the future watch app (and any other device). No watch-specific endpoint exists or is needed.

**Feed location** — the same call the phone makes, authenticated as the user (Firebase ID token):

```
PATCH /api/userinfo/me/location
{ "lat": 40.3916, "lng": -111.8508, "source": "watch", "accuracyM": 12 }
→ 204 No Content    (400 when lat/lng missing or out of range; 401 without a token)
```

- `source`: `"phone" | "watch" | "web"`. Anything else is stored as null — never rejected — so a
  device type added later keeps working before the server learns its name.
- `accuracyM`: the platform's horizontal accuracy in metres, any positive number; rounded and
  clamped to 1–100000. Omit it when the platform does not report one.
- During a live-location session the watch may instead post points to
  `PATCH /api/live-location/sessions/{id}/point` with the same optional `"source": "watch"`.
- Send fixes at the cadence the phone uses (the server throttles its own reverse geocoding;
  there is no server-side rate limit on this endpoint to rely on).

**What the server derives from each fix** (`LocationPresenceService.applyFix`, one code path for
both endpoints — a watch fix and a phone fix are indistinguishable downstream except for the
recorded `source`):

1. `lastKnownLat/Lng/LocationAt`, `locationSource`, `locationAccuracyM`.
2. **Place presence** — among the user's OWN saved places with `sharePresence = true`, the
   nearest whose distance ≤ `radiusM + min(accuracyM ?? 0, 100)`. Stored as
   `current_place_id` + `current_place_since` (the arrival time, kept while the fix stays in the
   same place; cleared when outside all of them).
3. **"Last seen near"** — `Place.shortLabel()` from Nominatim, re-resolved only when the fix is
   ~2 mi (0.03°) from where the label was last resolved.
4. The jurisdiction zip (`lastKnownZip`), same geocode.
5. A roster frame to each group whose sharing gate is open.

**Privacy rules** (binding on any client that renders these):

- Nothing a device sends decides visibility. The roster's `LocationSharing` gate (per-group
  `always | check-in-only | never`) decides, and when it is closed EVERY location-derived field
  is null: `lastKnownLat/Lng/LocationAt`, `atPlace`, `lastSeenNear`, `locationSource`,
  `locationAccuracyM`, `inAlertIds`. A member who opted out and a member who never had a fix
  look identical, and there is no field saying why (locked 2026-07-02).
- "At <place>" only ever names a place the member saved AND opted to share
  (`sharePresence`, default off, per place), re-checked at read time; it carries the member's own
  label and kind, never coordinates or an address.
- The derived columns are server-owned: never on the raw `/api/userinfo` entity JSON and never
  writable through `PATCH /api/userinfo/{id}`.
- A watch must not cache or re-share other members' locations beyond what the roster payload
  it was served contains.
