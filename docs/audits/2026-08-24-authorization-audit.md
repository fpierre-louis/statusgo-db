# Authorization audit — every endpoint that fails to check *who is asking*

**2026-08-24. Read-only audit. Nothing fixed.** All 71 resource classes swept,
plus the service layer behind them. BE at `299bb95`.

**Scope trigger:** two endpoints were found by accident that verify the caller is
signed in but never verify they are a member of the household whose data they
serve. The instruction was to audit before fixing, because two found by accident
suggests more. **There are 21, across four classes, and two of them are worse
than the two that started this.**

---

## 0 · The amplifier — read this first

`config/SecurityConfig.java:38-52`:

```java
.requestMatchers("/api/**").permitAll()
.anyRequest().permitAll()
```

**There is no framework-level authorization anywhere in this application.** The
filter is authenticate-only: it populates the security context when a valid token
is present and never rejects. Every gate in the product is a hand-written
`AuthUtils` / `HouseholdAccessService` / `GroupRole` / `PlatformAccessService`
call inside a controller method.

**A missing call is therefore not a weaker gate. It is no gate.** That is what
turns each omission below from a code-review nit into a live exposure, and it is
why the count matters more than any single instance.

---

## 1 · Severity-ordered findings

### 🔴 CRITICAL — reachable with one free account, no id guessing

**1.1 · `GET /api/emergency-groups` — every user's emergency contacts, including
medical information.** `EmergencyContactGroupResource.java:30-36`

```java
AuthUtils.requireAuthenticatedEmail();
return groupService.getAllGroups()...      // → groupRepo.findAll()  (service:29-31)
```

`EmergencyContact` (`domain/EmergencyContact.java:19-28`) carries `name`, `phone`,
`email`, `address`, `role`, **`medicalInfo`**, `radioChannel`.

One GET returns the name, phone, home address and medical information of every
emergency contact of every user in the database. **No id required.** The comment
in the code claims auth-only is "still better than fully open" — with the global
`permitAll` above, a free Firebase signup is the entire cost of entry.

**1.2 · `GET /api/userinfo/{id}` · `/email/{email}` · `/firebase/{uid}` — the raw
`UserInfo` entity.** `UserInfoResource.java:91-113`

Returns `ResponseEntity<UserInfo>` — the **entity**, not a DTO — containing
`phone` (:63), `address` (:66), **`lastKnownLat`/`lastKnownLng`** (:246-249, live
device location), **`fcmtoken`** (:93, a push-spoofing primitive), and
`baseHouseholdId` (:143).

**The same file proves this is an oversight, not a policy.** Fifteen lines above,
the *bulk* dump is gated and audit-logged:

```java
UserInfoResource.java:80-81
    access.require(PlatformPermission.VIEW_PII);
    adminAuditLogService.record(access.auditActorEmail(), "VIEWED_PII", …
```

The bulk route requires `VIEW_PII` and writes an audit record. The singular routes
hand the identical entity to anyone with an account. `/email/{email}` makes it
**enumerable without knowing any id**, and defeats the deliberate
"exact-email lookup returns confirmation-only, no profile preview" contract in
`UserSearchResource.java:82-88`.

**1.3 · `DELETE /api/images?keyOrUrl=…` — delete any image in the bucket.**
`ImageResource.java:57-65`

```java
String requester = AuthUtils.requireAuthenticatedEmail();
// TODO: stronger ownership check … Today any signed-in user can delete any image.
storage.delete(keyOrUrl);
```

The code documents its own vulnerability. Image keys are not secret — they ship in
every `PostDto.imageKey`, `Group.logoImageUrl`, `UserInfo.profileImageUrl`, and
`HouseholdManualMemberDto.photoUrl`. Scrape keys from any readable feed, then
permanently destroy every avatar, group logo, post photo and work-order evidence
photo. **Irreversible, cross-tenant, unrate-limited.**

### 🟠 HIGH — household/group data to any signed-in stranger

| # | Endpoint | Exposure | R/W |
|---|---|---|---|
| 1.4 | `GET /api/demographics` | every household's `infants`/`kids`/`teens`/`adults`/pets + `ownerEmail` + `householdId` — *a queryable index of which homes contain small children* | R |
| 1.5 | `GET /api/mealPlans` | every household's meal plan; primarily an `ownerEmail → householdId` map, which converts every id-guessing defect below into a lookup | R |
| 1.6 | `GET /api/groups/{groupId}` | raw `Group` entity — `memberEmails`, `adminEmails`, `address`, `lat`/`lng`. **Households are Group rows**, so this is a family roster + home coordinates | R |
| 1.7 | `GET /api/groups/{groupId}/readiness-summary` | per-household `ownerEmail` + live "who needs assistance"; `buildReadinessSummary` takes **no caller parameter at all**, so it cannot gate | R |
| 1.8 | `/api/households/{id}/manual-members` (GET/POST/PATCH/DELETE) | children's names, ages, relationships — readable *and deletable*; deletion cascades into accompaniment links | **R+W** |
| 1.9 | `/api/households/{id}/accompaniments` (GET/POST/DELETE) | who-is-with-whom; **an outsider can insert themselves as the supervising adult for another family's child**, or release a real guardian's claim | **R+W** |
| 1.10 | `GET /api/households/{id}/events` | the family's activity timeline with member names, emails, avatars | R |

**1.6 and 1.7 are the discovery mechanism** that makes the rest practical: one
call yields the household ids needed for 1.8, 1.9, 1.10 and 3.1.

**1.10 is the cleanest proof that this is an omission pattern rather than a design
decision** — the other two endpoints *in the same file* gate correctly, using a
service already injected in that class:

```
HouseholdEventResource.java:49   AuthUtils.requireAuthenticatedEmail();          ← GET, ungated
HouseholdEventResource.java:74-75  … access.requireCanReadHousehold(caller, householdId);
HouseholdEventResource.java:107-108 … access.requireCanReadHousehold(caller, householdId);
```

### 🟡 MEDIUM — private-thread writes and roster leaks

- **`POST /api/group-post-comments`** — identity spoofing is blocked, membership
  is not. Injects content into any private family or agency chat, delivered to
  every member as a **push notification**, by incrementing `postId`.
- **Four reaction endpoints** (group post, group comment, post, post comment) —
  the roster read returns `EmojiReactionDto(userEmail, addedAt)`, leaking the
  **email addresses** of everyone who reacted in a private thread.
- **`POST /api/userinfo/profiles/batch`** — unbounded email list, no rate limit,
  defeats the opt-in discovery contract and its 30/min limiter in one call.

### 🔵 ZERO-AUTH — reachable with no account at all

**`GET /api/households/{householdId}/commerce-status`** —
`CommerceStatusResource.java:30-34` imports no `AuthUtils` whatsoever. Returns
`"household_checkin"`, `"deployed_plan"`, `"area_alert"` or `null`.

**An anonymous caller can poll household ids and learn, per family and in real
time, whether that household is currently in an emergency** — a check-in is
running, or they have activated and deployed their evacuation plan. It also
confirms household existence, making it a free enumeration oracle for everything
above.

Classified as an omission, not intent: every sibling household endpoint
(`FoodPlanResource`, `HomeStockpileResource`, `EvacuationAdvancedResource`,
`HouseholdPresenceResource`) uses `access.requireCanReadHousehold`.

**`GET /share/group/{groupId}`** — public by design for invite links, but has no
`groupType` or visibility filter, so a bot User-Agent gets
`"<Household name> on SitPrep · 4 members"` for any **private household**.

---

## 2 · The original finding, corrected upward

`MapPlaceResource` → `MapPlaceService.forHousehold`. The resource gate is
**correct**; the leak is in the service. Two things the original report missed:

1. **There are two fallbacks, not one** — meeting places (`:88-90`) *and*
   evacuation-plan shelters (`:111-112`).
2. **The reachability cause is a missing `groupType` filter.**
   `MapPlaceResource.java:38` uses `groupRepo.findByGroupId(householdId)` with no
   type check, and `isMember` accepts a member of *any* group.
   `HouseholdAccessService.household()` deliberately filters
   `"Household".equalsIgnoreCase(g.getGroupType())`; this hand-rolled check does
   not. That is what lets a member of a business or neighborhood circle reach it:
   both household-scoped queries return empty (no `MeetingPlace` row can carry a
   non-household group id), so the owner-email fallback fires.

**Leaked:** the group owner's personal meeting places (`name`, `address`,
`phoneNumber`, `lat`/`lng`) and shelter destinations — to every member of a group
they merely administer.

**Three more service methods share this household→owner-email substitution shape:**
`CommerceSuppressionService.suppressionReason` (reachable *anonymously* via the
zero-auth endpoint above), `RiskProfileService.resolveFor`, and
`HouseholdReadinessService.commsFor` (reads the owner's personal emergency
contacts and meeting places).

**Not every `findByOwnerEmail` is this bug.** Twelve other call sites were checked
and are correctly scoped — the resource gates on `requireCanReadPlanDataFor` or
forces the owner email from the token. The defect is specifically
*household-id in → owner's personal record out*.

---

## 3 · The correct pattern already exists

**Thirteen resources gate correctly**, so the fix is conformance, not invention:

- `HouseholdPresenceResource`, `HouseholdPlansResource`, `FoodPlanResource`,
  `EvacuationAdvancedResource`, `HomeStockpileResource`,
  `HouseholdChallengesResource`, `WeeklyCheckInResource`, `HouseholdRitualResource`
  — `access.requireCanReadHousehold` / `requireCanAdminHousehold`
- `GoBagResource` — the right pattern for **child-resource ids**: resolve the
  bag's household first, then gate
- `HouseholdPetService` — gate in the **service** layer. Its resource looks
  identical to the broken manual-members one; the difference is entirely in the
  service. **Pick one convention and apply it consistently.**

---

## 4 · Recommended fix order

Ordered by blast radius, not by effort.

1. **Delete or `VIEW_PII`-gate the three `findAll()` dumps** (emergency contacts,
   demographics, meal plans). Three lines. Removes the largest exposure *and*
   kills the `ownerEmail → householdId` maps that make everything else targetable.
2. **Gate `DELETE /api/images`** — destructive and irreversible.
3. **Return a DTO, not the entity, from the three `UserInfo` reads** — or
   `requireSelf`.
4. **Add `requireCanReadHousehold` to manual-members, accompaniments, and the
   events GET.** `HouseholdAccessService` is already injected in the third.
5. **Gate `GET /api/groups/{groupId}`** (or return the sanitized
   `GroupPreviewDto` that already exists for this reason) and thread a caller into
   `buildReadinessSummary`.
6. **`MapPlaceResource`** — filter `groupType="Household"`, or just call
   `HouseholdAccessService.requireCanReadHousehold`, which already does. Then
   condition the two owner-email fallbacks.
7. Route the reaction services and `createCommentFromDto` through the `canRead`
   authorizers that already exist one class over.
8. Gate `commerce-status`; add a visibility filter to the group share preview.

**Longer term, and the only durable fix:** flip `/api/**` from `permitAll` to
`.authenticated()` and make omission fail *closed*. Every finding here exists
because the default is open, so forgetting a line produces a working endpoint
rather than a 403. That flip needs its own lane — the public-by-design routes
(`/api/public/**`, share links, the recipient activation map, geocode proxies)
must be enumerated and explicitly permitted first.

---

## 5 · Method

All 71 resource classes read. Every claim above carries file:line and was
re-verified by hand against the source before being recorded here — the
`SecurityConfig` posture, the three critical findings, the `HouseholdEventResource`
same-file contrast, and the `MapPlaceResource` missing type filter were each read
directly rather than taken from the sweep.

Nothing was modified. No fixes applied.
