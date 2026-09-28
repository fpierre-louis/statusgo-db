# EXEC — sign-out releases the push token; another user's phone is members-only

Owner-authorised 2026-09-27 ("yes, go"). Both leaks were verified before fixing.

## 1 · Signing out never released the device's push token

**Cause, verified.** `AuthContext.logout` sends `PATCH /api/userinfo/{id}
{"fcmtoken": null}`. `patchUserById` skips every null value
(`if (rawKey == null || value == null) return;`), so the token stays and every
sender (`NotificationService` and 18 schedulers/services read
`UserInfo.fcmtoken` — the only store) keeps addressing the signed-out device.
Reproduced on prod: `agent-a` set a token, sent the app's exact sign-out call →
200, token still stored.

**Fix.** `DELETE /api/userinfo/me/fcm-token` with `{ "token": "<this device's>" }`
— clears only when the stored token IS this device's, so signing out on one
phone cannot silence a newer phone (one token per user; last registration
wins). No token supplied → clear (the device cannot tell; a signed-out device
still receiving pushes is the worse failure, and the other device re-registers
on its next start). Frontend: `logout` asks the SDK for the current token
(best effort) and calls it — no profile lookup first; the verified email is
the key.

## 2 · Any signed-in user could read any user's phone

**Cause, verified.** `GET /api/userinfo/email/{email}` (and `/{id}`,
`/firebase/{uid}`) strip `SELF_ONLY_FIELDS` for other callers — but `phone` was
left off on purpose (2026-08-24 note) because two screens read it cross-user:

- `HHPersonSheet` fetched a household member's whole profile for its Call row.
  The household roster already carries `phone`, members only (BE `7a62e22`).
- `MapView` fetched each subgroup owner's profile for "Call owner" — and fell
  back to the string `"Phone number not available"`, which then became the
  `tel:` target.

**Fix.**
- `GET /api/groups/{groupId}/owner-contact` → `{ownerEmail, phone}` for a caller
  who is a member of the group, or of a parent group where the link exists on
  BOTH sides (parent lists the child in `subGroupIDs`, child lists the parent in
  `parentGroupIDs`). One-sided would let anyone who creates a group list a
  stranger's group as its "subgroup" and read that owner's phone. Anything
  else → 404 (indistinguishable from unknown).
- Frontend: `HHPersonSheet` reads `person.phone` (carried from the roster);
  `MapView` reads the owner contact endpoint; no fallback string as a phone.
- Then `phone` joins `SELF_ONLY_FIELDS`.

## Acceptance

1. Prod: after the fix, the app's sign-out releases the token (own device);
   a different device's token survives.
2. Prod: `agent-a` reading `agent-b` by email gets no `phone`; reading itself
   still does.
3. Household sheet Call row and subgroup "Call owner" still work for members.

## Checklist

- [x] Reproduce #1 on prod — *verified by: agent-a `PATCH {fcmtoken:"agent-probe-…"}` → `PATCH {fcmtoken:null}` 200 → stored token unchanged*
- [x] Reproduce #2 on prod — *verified by: agent-b set a fictional `555-0100`; agent-a (a stranger) `GET /userinfo/email/sitprep-agent-b@…` → `phone: 555-0100` (fcmtoken/address/location already stripped)*
- [x] BE: token release endpoint + service + tests — *verified by: `PrivacyPushTokenAndPhoneTest$PushTokenRelease` 4/4 (own token cleared; a newer phone's survives with no write; no token → clears; nothing stored → no-op)*
- [x] BE: owner-contact endpoint + service + tests — *verified by: `$OwnerContact` 6/6 (member gets it, case-insensitive; two-sided parent gets it; parent-lists-only refused; child-claims-only refused; stranger and unknown group identical; blank phone → null, never a placeholder)*
- [x] BE: `phone` → `SELF_ONLY_FIELDS` — *verified by: `UserInfoReadScopingTest.phoneIsSelfOnly` (was `phoneIsDeliberatelyStillVisibleCrossUser`, which pinned the loose end this closes: stranger → no key, subject → own number); full `./mvnw package` 1104/0/0*
- [x] FE: logout, HHPersonSheet, MapView; tests; build — *verified by: Status Now `45f50984a`; vitest 1239/1239; build clean; lint: only pre-existing noInlineConfig warnings. HHPersonSheet's two callers (Family page, Home) both build people from the member view, which carries `phone`*
- [x] Deploy BE, then FE; verify on prod — *verified by: statusgo-db release of `f9df1a3`; FE Heroku v62 + Netlify sitprep.app both serve `me/fcm-token` in the entry bundle. On api.sitprep.app with the test accounts:*
  - *token: stored `agent-probe-…`; `DELETE /me/fcm-token {another device's token}` 204 → unchanged; `{this device's}` 204 → null*
  - *phone: agent-a reading agent-b by email → no `phone` key; agent-b reading itself → its number*
  - *owner-contact on agent-b's household: stranger 404; member 200 `{ownerEmail, phone}`*
  - *roster: agent-b's `/groups/{hh}/member` carries its phone; agent-a gets 403 for that roster*
  - *cleanup: the fictional number cleared*
