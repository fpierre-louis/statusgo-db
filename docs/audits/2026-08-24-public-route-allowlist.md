# A9 — the allowlist for flipping `/api/**` to `.authenticated()`

**2026-08-24. Read-only. Nothing flipped.** This is the list the owner asked for
before the flip, not the flip.

A1–A8 fixed instances. **A9 fixes the class**: while `/api/**` is `permitAll` and
the auth filter never rejects, forgetting a gate produces a *working endpoint*
rather than a 403. Every finding in the authorization audit exists because the
default is open.

The flip is one line. The risk is entirely in this list: a public-by-design route
that is not named here starts answering **401** the moment it ships, and several
of them are the flows a stranger hits *first* — an invite link, a shared
evacuation plan, a Stripe webhook.

---

## 0 · Method, and what it can and cannot tell you

All 314 handler methods were enumerated from source and classified by whether the
handler requires a signed-in caller.

**The distinction that matters, and the one a naive sweep gets wrong:**

| Call | Meaning | Under the flip |
|---|---|---|
| `AuthUtils.requireAuthenticatedEmail()` | **Requires** a token; throws without one | unaffected |
| `AuthUtils.getCurrentUserEmail()` | **Reads** the token, returns `null` when absent | **breaks — this is an anonymous-tolerant route** |

A first pass here treated both as "gated" and produced a clean-looking list that
was missing the entire `/api/ask` read surface. Anonymous-tolerance is expressed
by using the *nullable* accessor, so that is the signal the list below is built
on — plus the FE routing table, which says which pages render without
`ProtectedRoute`.

Two limits, stated rather than papered over:

- **Static analysis cannot prove a route is never called anonymously.** Where a
  route's public-ness depends on a runtime condition, it is marked
  ⚠ and the condition is named.
- **Third-party callers do not appear in the frontend at all.** Stripe is the one
  known case, found by reading the handler rather than by tracing a caller.

---

## 1 · The allowlist — must stay `permitAll`

Ordered by what breaks if it is missed.

### 1.1 Recipient activation — the shared evacuation plan

| Route | Evidence |
|---|---|
| `GET /api/plans/activations/{id}` | Handler: *"Auth-OPTIONAL (SEC-3): the owner / household (verified token) gets the full snapshot; a logged-out recipient link holder gets the data-minimized recipient view."* |
| `GET /api/plans/activations/{id}/map-public` | Handler: *"PUBLIC recipient emergency-map payload — link-possession (recipients may have no SitPrep account)."* |
| `POST /api/plans/activations/{id}/acks` | Handler: *"this endpoint is intentionally un-authed (recipients may not have a SitPrep account)"*, with a per-IP rate limit standing in for auth. |

**This is the highest-stakes entry in the document.** `/deployedplan` is a public
FE route; a recipient tapping "I'm safe" from a shared link may have no account
at all. Flipping these to `.authenticated()` means a person in an emergency
cannot tell their family they are safe.

`GET /{id}/map` (no `-public`) and `GET /{id}/acks` are **not** on this list —
both are owner-scoped and already require or resolve a caller.

### 1.2 Invite links and share previews

| Route | Evidence |
|---|---|
| `GET /api/groups/{groupId}/preview` | Handler: *"Deliberately NOT auth-gated: an invite-link recipient lands here BEFORE signing in, so requiring a token would 401 the very flow this endpoint exists to serve."* Confirmed in the FE: `JoinGroupPage.js` calls `fetchGroupPreview`, and `/joingroup` renders without `ProtectedRoute`. |
| `GET /share/group/{id}`, `/share/i/{id}`, `/share/post/{id}` | Crawlers (Facebook, Discord, iMessage) carry no token. **Not under `/api/`** — they fall to `anyRequest()`, so the flip does not touch them. Listed so the next person does not "tidy up" `anyRequest()` and take them out. |

### 1.3 Stripe

| Route | Evidence |
|---|---|
| `POST /api/billing/webhook` | Handler: *"Reachable without an auth token — the `Stripe-Signature` HMAC, verified against the webhook secret, is the authentication."* |

Stripe will never send a Firebase token. A 401 here is silent billing failure
with retries, which is the worst shape: nothing visibly breaks in the app.

### 1.4 Ghost-tenant outreach opt-out

| Route | Evidence |
|---|---|
| `GET /api/public/outreach/opt-out` | Clicked by non-registered agency contacts. Security is the HMAC-signed token in the link. |

**Already correct and already ordered correctly** — `SecurityConfig:49` declares
`/api/public/**` *before* the general `/api/**` rule, with a comment saying it is
so this survives exactly this flip. Nothing to do.

### 1.5 Ask — reads are anonymous by product decision

| Route |
|---|
| `GET /api/ask/questions`, `/questions/top`, `/questions/{id}` |
| `GET /api/ask/tips`, `/tips/{id}` |
| `GET /api/ask/search` |

**Not on the owner's original list, and it would have broken.** `CLAUDE.md`
records the 2026-05-04 decision that Ask reads are anonymous, and the FE routing
table backs it: `/ask`, `/ask/q/:id` and `/ask/tips/:id` render **without**
`ProtectedRoute`, while `/ask/q/new` and `/ask/tips/new` are wrapped. Every read
handler uses the nullable `getCurrentUserEmail()` to resolve an optional viewer;
every write uses `requireAuthenticatedEmail()`. The split is already drawn
correctly in the code — the flip would simply overrule it.

### 1.6 Community map — guest browsing

| Route | Evidence |
|---|---|
| `GET /api/community/map` | Class doc: *"Auth is OPTIONAL — a guest panning the community map gets public data; a signed-in viewer additionally gets role-aware `viewerRole` on group pins."* |

Also not on the original list.

### 1.7 Public marketing and pre-signup surfaces

| Route | Public FE page |
|---|---|
| `GET /api/retail/products` | `/emergency-supplies` — App.js comments it *"Public supplies catalog — affiliate reviewers must reach it logged-out."* |
| `GET /api/readiness/assessment/questions`, `POST /assessment/evaluate` | `/sitprep-quiz` (`EmergencyAssessment`), rendered without `ProtectedRoute` |
| `POST /api/agency/requests` | `/agencies` (`AgencyLandingPage`), a public sales-lead surface; handler uses the nullable accessor ⚠ *no direct call site found in the page — verify before relying on this row* |
| `POST /api/userinfo/firebase` | Account provisioning ⚠ — see §3 |

---

## 2 · On the owner's list, and the evidence says otherwise

Two of the five categories named in the instruction do not need to be public, and
one of them is **safer closed**. Bringing these back rather than quietly
allowlisting them, because widening `permitAll` is the one direction this lane is
not supposed to move.

### 2.1 `GET /api/geocode/search` · `/api/geocode/reverse` — recommend **NOT** allowlisting

Every frontend caller is an authenticated surface: `MapView`,
`DiscoverMapExplorer`, `MeetingPlaces`, `SelectOrigins`, `ActivatePlanDesign`,
`CreateHouseholdGroup`, `CivicFields`, `MapRadiusPicker`,
`LocationLockedCallout`. `WelcomeWizard` reverse-geocodes **server-side** on save
and does not call the proxy.

And it is not neutral to leave open. This is an **unauthenticated proxy to
Nominatim**, which is a free service with a usage policy. An open proxy is a
free-to-abuse relay whose consequences land on our IP — rate limiting or a ban
that breaks address search for every real user.

**Recommendation: let the flip close it.** If something turns out to need it
pre-auth, that is a 401 in a dev console, not a broken emergency flow.

### 2.2 `GET /api/alerts/active` · `/feed` · `POST /refresh` — recommend **NOT** allowlisting

The pre-auth alert path does not use these. `CrisisBand`'s `useExternalAlerts`
and both `useActiveHazards` implementations pass `skipBackendCache: true`, which
goes **direct to NWS**, bypassing this API entirely. The callers that do hit
`/api/alerts/active` (`FemaWeatherMVP`, `MapView`, `DiscoverMapExplorer`) are all
behind `ProtectedRoute`.

`POST /api/alerts/refresh` deserves its own line: it is a **write** — it triggers
a refresh of the server's alert cache — and it is currently callable by anyone on
the internet with no account.

---

## 3 · Decide before flipping

Four routes where the honest answer is "this needs a human", not a guess.

| # | Route | The question |
|---|---|---|
| 3.1 | `POST /api/userinfo/firebase` | Account provisioning on first sign-in. A user **has** a Firebase token by the time this runs — Firebase auth completes first, then the app provisions the row — so it should survive the flip. **But if it 401s, nobody can create an account.** Verify against a real cold-start signup before flipping, not by reading. |
| 3.2 | `GET /api/config/defaults` | Server-tunable radius defaults. `useAppDefaults` catches failures and falls back to bundled constants, so a 401 is **invisible** — the app looks fine and the server-side knob silently stops reaching logged-out surfaces. That is the exact failure its own header comment already describes having missed once. Cheap to allowlist; recommend allowlisting. |
| 3.3 | `GET /api/verified-publishers`, `/{email}` | Only consumers are `MarketplacePage` and `BusinessProfilePage`, both behind `ProtectedRoute`. Safe to close — **unless** public business profiles are on the roadmap, which is a product call. |
| 3.4 | `GET /api/alert-mode` | **No frontend caller found anywhere.** Either dead code or an untraced admin surface. Worth resolving on its own terms rather than being swept into an allowlist. |

---

## 4 · Already permitted, unaffected by the flip

`/ws/**`, `/app/**`, `/topic/**` (STOMP), `/actuator/**`, `/v3/api-docs/**`,
`/swagger-ui/**`, and `OPTIONS /**` (CORS preflight — its own matcher, first).

`anyRequest().permitAll()` covers everything outside `/api/**`, which is what
keeps `/share/**` and `/notifications` reachable. **Flipping `/api/**` does not
touch that**, and it should not be tightened in the same change — one variable at
a time.

---

## 5 · The shape of the flip

```java
.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
.requestMatchers("/ws/**", "/app/**", "/topic/**").permitAll()
.requestMatchers("/api/public/**").permitAll()
// ... the §1 allowlist here, BEFORE the /api/** rule ...
.requestMatchers("/api/**").authenticated()      // <- the change
.requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
.anyRequest().permitAll()
```

**The filter has to start rejecting too.** It currently runs in
verify-but-never-reject mode (`SecurityConfig:34-37`). `.authenticated()` alone
is not enough if nothing populates or refuses; confirm the filter surfaces a 401
for an absent or invalid token before trusting the matcher.

**Verify per-row, not in aggregate.** Every §1 entry has a reproducible check: an
invite link opened logged-out, a `/deployedplan` link acked from a private
window, a Stripe test event, `/ask/q/:id` with no session, `/sitprep-quiz`
end-to-end. A build that compiles proves nothing here — the failure mode is a
flow that only strangers use, which is exactly the flow nobody on the team
exercises.

---

## 6 · What A9 buys

Every finding in the authorization audit was a *missing line*. With
`permitAll`, a missing line is a working endpoint; after the flip it is a 401.
That converts this whole class of defect from something you find by auditing into
something you find the first time you call it — which is the only durable fix,
and the reason this is worth doing carefully rather than quickly.
