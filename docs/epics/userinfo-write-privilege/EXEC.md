# EXEC — a user cannot grant themselves privileges through their own profile

Owner-authorised 2026-09-27 ("take care of the security"). Found during the
map-ideal backend unit (`docs/epics/map-ideal-backend/EXEC.md`, "Finding, not
fixed").

## The defect, verified in code before fixing

`PATCH /api/userinfo/{id}` (`UserInfoService.patchUserById`) is a **reflective
setter over every `UserInfo` field** except `id`, `userEmail` and the seven
server-derived location fields. `ensureOwns` stops you editing someone else's
row — it does nothing about WHICH fields of your own row you may write.

What that reaches, traced to the readers:

| Field a user could set on themselves | What trusts it |
|---|---|
| `verifiedPublisherEmergencyPostingEnabled` | `PostService` — the ONLY gate on posting an `official` alert with `officialTier = emergency` (pinned) when not authoring as an agency group |
| `verifiedPublisher`, `verifiedPublisherKind`, `verifiedSince`, `verifiedBy`, `verifiedPublisher*` | `PostService` news gate; verified badges on posts, profiles, discovery, the map |
| `subscription`, `subscriptionPackage`, `dateSubscribed`, `subscriptionOverride*` | `MeService` / billing entitlement |
| `firebaseUid` | identity binding — `ensureOwns` accepts a uid match; lookups by uid |
| `baseHouseholdId` | the base household the dashboard and household-owned plans anchor to — bypasses `PATCH /me/base-household`'s membership check |
| `groupLocationSharing`, `searchable` | privacy settings with their own validated endpoints |
| `lastKnownLat/Lng/LocationAt`, `userStatusLastUpdated`, `statusSetByEmail`, `lastActiveAt`, … | server-maintained facts |

Two more paths with the same class of hole:

- `PUT /api/userinfo/{id}` (`updateUserById`) copies `subscription`,
  `subscriptionPackage`, `dateSubscribed` and a body `firebaseUid` straight in.
- Account creation (`applyInitialSystemDefaults`, both upserts) takes
  `subscription*` / `dateSubscribed` from the request body.

Not affected: the sign-in upserts' field copy (`applyPatch`) is already an
explicit allow-list.

## Scope

- PATCH becomes an **allow-list**. Allowed = what a user legitimately edits
  about themselves, derived from every frontend caller (10 call sites):
  profile (`userFirstName`, `userLastName`, `title`, `phone`, `address`,
  `profileImageUrl` [host policy unchanged], `bio`, `coverImageUrl`,
  `profileVisibility`), own status (`userStatus`, `statusColor`), push token
  (`fcmtoken`), manual ZIP (`lastKnownZip`), onboarding step timestamps
  (`onboarding*At`), and the denormalised membership cache
  (`joinedGroupIDs`, `managedGroupIDs` — nothing authoritative reads them:
  `MeService` reads membership from the Group side, and the only query over
  `joinedGroupIDs` has no callers).
- Keys that are not `UserInfo` fields (the profile editor also sends
  `latitude`, `longitude`, `dateOfBirth`) and `id` / `userEmail` stay ignored,
  exactly as today — rejecting them would break the editor.
- **Any other real `UserInfo` field → 403**, checked for every key BEFORE
  anything is written (no partial application), outside the reflective
  try/catch that swallows exceptions.
- PUT stops copying `subscription*`, `dateSubscribed` and a body `firebaseUid`
  — silently, because PUT's contract is "echo the whole record back"
  (`LeaveGroup` sends `{...user, joinedGroupIDs}`), so those values arrive on
  every legitimate call.
- Creation ignores client `subscription*` / `dateSubscribed`: server defaults
  (`Basic` / `Monthly` / now) — the same values the app already sends.

**Out of scope:** the endpoint-level design (a typed write DTO would be the
cleaner end state; the allow-list is the minimal safe change); `fcmtoken: null`
being skipped by the PATCH null-guard (sign-out never clears the token) — a
separate finding, logged below; `ManageMembersModal` PUTs OTHER users' rows,
which `ensureOwns` already 403s — a separate broken admin flow.

## Acceptance

1. On prod, before the fix, a test account CAN set
   `verifiedPublisherEmergencyPostingEnabled` on itself (proves the cause).
2. After the fix, the same PATCH is 403 and the stored value is unchanged.
3. Every frontend PATCH shape still succeeds.
4. `./mvnw package` green; deploy verified live.

## Checklist

- [x] Reproduce on prod with a disposable test account; revert the flag — *verified by: `sitprep-agent-b@yopmail.com` (plain user) `PATCH /api/userinfo/573454fd-…` `{"verifiedPublisherEmergencyPostingEnabled":true,"verifiedPublisher":true}` → **200**, response `true/true`; reverted with the same hole → GET shows both `false`, `verifiedSince/By/Kind` null*
- [x] Allow-list in `patchUserById` (validate-all-then-apply, 403 outside the swallowing try) — *verified by: `UserInfoWritePrivilegeTest` 23 privileged/server-owned fields → 403; the reproduced body → 403 with both flags still false; a refused key stops the whole patch (allowed `bio` not written); a refused key with a null value is still refused*
- [x] Every frontend shape still passes — *verified by: the editor diff incl. non-field keys (`latitude`, `longitude`, `dateOfBirth`, `userEmail`) saves; onboarding timestamps (ISO strings → Instant), `lastKnownZip`, `fcmtoken`, own status all save*
- AMENDED (found while testing): `joinedGroupIDs` / `managedGroupIDs` are **ignored**, not allowed. `Members.js`'s PATCHes of them have NEVER landed — the fields are `Set<String>`, JSON arrives as a List, and the reflective setter's exception was swallowed. Ignoring them keeps the caller's 200 and stops a client overwriting the server-maintained cache; PUT (`LeaveGroup`) still writes them, typed.
- [x] PUT: drop `subscription*`, `dateSubscribed`, body `firebaseUid` — *verified by: `putIgnoresSubscriptionAndABodyUid` (and `joinedGroupIDs` from the same body still lands)*
- [x] Creation: ignore client `subscription*` / `dateSubscribed` — *verified by: `aNewAccountCannotChooseItsOwnPlan` (Premium/Lifetime/2020 in → Basic/Monthly/now)*
- [x] ADDED (same class, found while tracing): `POST /api/userinfo` kept a body `firebaseUid` and only filled the verified uid when the body had none — a caller could bind their row to another account's uid. Now always the verified uid, like the email. *verified by: read-back of the resource (the `/firebase` endpoint already did this)*
- [x] Update the server-derived test (now 403 rather than a silent skip) — *verified by: `SavedPlacePresenceFieldsTest` 7/7*
- [x] `./mvnw package` — *verified by: 1093 tests, 0 failures, 0 errors*
- [ ] Push; verify live
- [ ] Re-run the prod reproduction: 403

## Logged, not fixed

- `fcmtoken: null` (sign-out) is dropped by the PATCH null-guard, so signing
  out never clears the device's push token — pushes can keep reaching a
  signed-out device. Separate unit.
- `ManageMembersModal` PUTs OTHER members' rows; `ensureOwns` 403s them — that
  admin flow is already broken.
- Membership cache writes from `Members.js` are dead code on the client.
