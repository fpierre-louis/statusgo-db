# Backend DTO / outbound-data restriction audit — 2026-06-11

**Author hat:** Senior Database Architect + Senior Backend Engineer
**Trigger:** Google-SSO users showed **initials instead of their real
avatar** everywhere. Root cause was `DtoImages.avatar()` *deliberately
nulling* any non-R2 `http(s)` URL — including stable Google/Facebook
account-photo CDNs — on the `/api/me` read path. The raw column held the
correct URL; the DTO layer threw it away.

That bug is a *symptom of a pattern*: defensive "shape / sanitize /
normalize before we ship" code that, in the name of safety, silently
**drops or distorts data the FE legitimately needs**. This audit sweeps
the `dto`, `service`, and `resource` trees for that pattern and sorts
every finding into three buckets so we simplify the genuinely-harmful
restrictions **without touching the ones that are load-bearing**
(privacy, security, pagination, write-integrity).

---

## Verdict up front

The scary-sounding list of "restrictions" is mostly **justified**.
After classification, the genuine over-restriction was **narrow and
concentrated in the image/URL resolver family** — which we have already
fixed. The rest is privacy redaction, pagination, preview-summary
truncation, and write-side validation that should stay.

So this is a *small* scope of work, not a rewrite. The highest-value
deliverable is (a) the image-resolver simplification (**done**), (b) two
small consistency/coverage follow-ups, and (c) a written principle so
this class of bug doesn't get reintroduced.

### Classification key

| Bucket | Meaning | Action |
|---|---|---|
| 🟢 **KEEP** | Restriction is load-bearing (privacy / security / perf / integrity). | Do **not** remove. Documented here so we stop re-flagging it. |
| 🟡 **VERIFY** | Probably fine, but has a sharp edge or a coverage gap worth confirming. | Small, low-risk check / tweak. |
| 🔴 **SIMPLIFY** | Drops or distorts real data with no load-bearing justification. | Remove the restriction; pass the data through. |

---

## 🔴 SIMPLIFY — genuine over-restriction

### 1. Image / URL resolver nulled stored URLs — **FIXED 2026-06-11**

`dto/DtoImages.java`. The old `resolve()` dropped every non-R2
`http(s)` URL to `null`, on the theory it might be an expiring Firebase
Storage signed URL. **We don't use Firebase Storage**, so the theory
never applied — and the rule mangled real, stable SSO photo URLs.

- **Was:** R2-public URL → pass; bare key → normalize; **everything
  else → `null`** (then a later patch added a Google/FB host allowlist —
  still a filter).
- **Now:** absolute URL (`http`/`https`/`//`/`data:`) → **pass through
  unchanged, no host check**; bare key → normalize via
  `PublicCdn.toPublicUrl`; blank → `null`. Applied to **both** `avatar()`
  and `cover()`.
- **Impact:** fixes initials-instead-of-photo for *all 16* avatar DTOs
  (self profile, post authors, comments, group/household rosters,
  search, follow, discover) in one change, because every site funnels
  through `DtoImages.avatar`.
- **Risk:** none meaningful. We now ship exactly what's stored; a broken
  URL renders the FE's existing initials fallback (same as before).
  Status: ✅ shipped, compiles.

> **Principle established:** outbound media is a *pass-through*, not a
> *gate*. The store is the source of truth. Never null a stored URL
> because the host "isn't ours" — if it was good enough to persist, it's
> good enough to ship. Validate at **write** time, not read time.

---

## 🟡 VERIFY — small follow-ups (low risk)

### 2. `hasProfileImage` / `hasCoverImage` flags can drift from the URL

`service/MeService.java:586` and `dto/PublicProfileDto.java:204`:

```java
private static boolean hasImage(String raw) {
    return raw != null && !raw.isBlank() && PublicCdn.toObjectKey(raw) != null;
}
```

The flag is computed via `PublicCdn.toObjectKey(...) != null`, but the
URL is now produced by the simpler pass-through in `DtoImages`. For the
realistic cases (R2 URL, R2 key, Google/FB URL) both agree, so today's
payloads are consistent (sample `/api/me` shows `hasProfileImage:true`
+ a populated `profileImageUrl`). **Edge drift:** a `data:` URI (or any
URL `toObjectKey` can't parse) would ship a non-null `profileImageUrl`
but `hasProfileImage:false`.
- **Fix (1 line each):** define the flag in terms of the resolver the
  URL actually uses — `DtoImages.avatar(raw) != null` — so the boolean
  and the string agree *by construction* (which is what the BE-03
  comment already claims).
- **Risk:** trivial; aligns two values that are already aligned in
  practice.

### 3. `PostDto` author/publisher null-until-enrich — **REAL GAP FOUND + FIXED 2026-06-11**

`dto/PostDto.java:254-314` — `fromEntity()` intentionally leaves
`requesterFirstName/…/profileImageUrl`, `authorType`, `verifiedState`,
`publisherScope`, etc. null; they're folded in by a second
`withAuthors()` / `withAuthoredAsGroup()` / `withEngagement()` pass. Fine
*as long as every outbound path enriches*. Swept all 8 `PostDto.fromEntity`
call sites:
- ✅ create (493→498), feed (609-619→705), reopen/lifecycle
  (`refetchAndBroadcast` 1003), createPost (988) — all enriched.
- 🔴 **`PostService.patch()` (was line 850) shipped a bare
  `PostDto.fromEntity(saved)`** in both the HTTP response AND the STOMP
  broadcast frame. Editing a post → author name/avatar + verified badge
  went **null** in the live feed until a full refetch.
- **Fix:** `patch()` now mirrors the canonical fold
  `withParentPosts(withAuthoredAsGroups(withAuthors(...)))`. ✅ shipped,
  compiles.

### 4. `PublicCdn.toObjectKey` drops `data:` / `blob:` (media reuse)

`util/PublicCdn.java:50,64` returns `null` for `data:`/`blob:`/`file:`
and for path-less URLs. Now that `DtoImages` passes `data:` through for
avatars, `toObjectKey` is only on the bare-key path, so this no longer
bites avatars. Leave as-is unless a future media surface needs `data:`
round-tripping. **No action now;** noted so it isn't re-discovered.

---

## 🟢 KEEP — load-bearing, do **not** "simplify"

These look restrictive but are correct. Removing them would leak data,
break pagination, or invite abuse. Listed so we stop re-litigating them.

### Privacy redaction (intentional, comment-documented)
- `dto/GroupPreviewDto.java` — withholds `memberEmails`/`adminEmails`/
  `pendingMemberEmails` from the non-member join preview; ships only
  counts + identity. **Keep** — this *closed* a roster-leak bug.
- `dto/CommunityDiscoverDto.java` (NearbyGroup) — ships computed
  `viewerRole` instead of raw email lists to strangers. **Keep** — same
  privacy rationale; it's the canonical `roleOf` two-path design.
- `dto/PublicProfileDto.java` `stub()` — privacy-gated profiles return
  `visible:false` + empty collections rather than PII. **Keep.**
- `resource/UserSearchResource` / `UserSearchDto` — typeahead ships only
  firstName/lastName/email/photo. **Keep.**

### Pagination / preview caps (perf, not data-hiding — full data is one endpoint away)
- Feed page cap 50 (`PostService.java:704`); verified-publisher 50
  (`VerifiedPublisherService.java:94`); discover 50
  (`CommunityDiscoverService` `MAX_RESULTS`); comments/inbox/group-chat
  page cap 100 (`PostCommentService`, `NotificationInboxService`,
  `GroupPostCommentService`); Ask 50 (`AskService`).
- Avatar **preview** caps of 4 (`MeService.java:260` member preview;
  `CommunityDiscoverDto` `MUTUAL_PREVIEW_LIMIT`) — these feed face-stacks
  that intentionally show "4 + N more"; the full roster has its own
  endpoint. **Keep all** — these are pagination/preview, not redaction.

### Preview-summary truncation (the full text is on the detail endpoint)
- Comment preview 80 chars (`PostCommentService.java:402`); group-post
  preview 50 chars (`GroupPostService.java:422`); push body 160
  (`AlertDispatchService`); alert area 237 (`AlertIngestService`).
  **Keep** — these are card/notification summaries, not the source data.

### Write-side validation (integrity / abuse bounds — validate at write, exactly right)
- ≤5 images/post, title-required-by-kind, paymentMethodsJson ≤4096B,
  assessment JSON ≤50KB, emoji ≤32 chars, push fan-out ≤500 recipients.
  **Keep** — these reject *bad input at write time*, which is precisely
  where validation belongs (and the opposite of the avatar bug, which
  validated at read time).

---

## Scope of work & plan

**Effort: small.** One fix shipped; two tiny follow-ups; one doc.

| # | Item | Type | Risk | Status |
|---|---|---|---|---|
| 1 | `DtoImages` → pass-through resolver (avatar + cover) | 🔴 simplify | none | ✅ done |
| 2 | `hasImage` flags → centralized `DtoImages.isPresent` (removed 2 dup helpers) | 🟡 verify | trivial | ✅ done |
| 3 | `PostDto.fromEntity` enrichment sweep → fixed `patch()` null-author bug | 🟡 verify | low | ✅ done |
| 4 | Add "outbound media is pass-through, validate at write" to BE conventions | doc | none | proposed |

**Sequencing**
1. Ship #1 (done) — restart the API to pick it up; the avatar renders
   from the stored URL on every surface.
2. #2 in the same small PR — one-line change in each of the two
   `hasImage` helpers; keeps flag/URL agreement provable.
3. #3 as a read-only sweep; only touch code if a real gap is found.
4. #4 — fold the principle into the backend conventions doc so the next
   "be defensive and null it" reflex gets caught in review.

**Explicitly out of scope / not doing:** removing any 🟢 KEEP item.
Those are privacy, pagination, summary, and write-integrity controls —
simplifying them would leak rosters/PII, break paging, or admit abuse.
The lesson from the avatar bug is narrow and precise: **don't gate
outbound data that's already trusted in the store — gate it on the way
in, not on the way out.**
