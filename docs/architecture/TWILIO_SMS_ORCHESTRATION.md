# Twilio SMS Alert Orchestration — Architecture Blueprint

> **STATUS: BACKLOG. Scheduled for post-launch phases.**
>
> Deliberately **not** implemented for the initial launch / App Store builds to
> keep them lean and free of third-party API cost and A2P registration lead
> time. No Twilio dependency, no migration, and no SMS code ship until this is
> pulled off the backlog. This document is the durable design reference so the
> work can start cold. Audited against the live backend (Spring Boot 3.4,
> Hibernate 6.6.2, Postgres 16, single 512 MB Heroku dyno `statusgo-db`,
> CloudAMQP provisioned-but-unwired) on 2026-07-11.

---

## 1. What this is

The B2B revenue engine: a backend worker that intersects **live NWS/USGS alerts**
with **agency geofences** and dispatches **Twilio SMS** to residents, while
**strictly enforcing each agency's `sms_allowance_monthly`** so nobody exceeds
their paid tier.

**Design thesis — extend, don't rebuild.** ~80% already exists as the *manual,
push-only* agency-alert path. This engine (a) **inverts** the geo-match
(alert → agencies instead of agency → users), (b) **swaps the channel**
(FCM → Twilio SMS), and (c) adds the two things that exist nowhere today: a
**fail-closed billing ledger** and a **durable outbox**.

### Pipeline at a glance
```
AlertIngestService  (EXISTS: NWS/USGS/FEMA -> in-memory snapshot, 5-min poll)
      | getSnapshot()   (reuse as-is)
      v
(1) MATCH worker  @Scheduled -- alert.geometry ∩ agency geofence --> atomic BILLING RESERVE --> bulk-insert
      |                                                                                          into sms_outbox
      v
(2) DRAIN worker  @Scheduled -- FOR UPDATE SKIP LOCKED, batch-bounded, exp. backoff --> TwilioSmsSender (Messaging Service)
      v
(3) Twilio status-callback webhook --> reconcile delivery + refund allowance on pre-accept failures
```

---

## 2. Audit — reuse vs. build (as of 2026-07-11)

| Capability | Status | Reuse / Build |
|---|---|---|
| Live NWS/USGS/FEMA feed | ✅ Exists | **Reuse** `AlertIngestService.getSnapshot()` / `NormalizedAlert{geometry}` (5-min poll, in-memory `AtomicReference` snapshot). Do not re-poll. |
| Scheduled dispatch-worker skeleton | ✅ Exists | **Clone** `AlertDispatchService.dispatchOnce()` (per-alert try/catch + Sentry, dedup-via-unique-index, radius cap). |
| Agency geofence model | ✅ Exists | **Reuse** `Group.jurisdictionLat/Lng/RadiusMiles` (≤50 mi) + `jurisdictionZips` + `agencyAuthorized`; `GroupRepo.findAuthorizedAgencies()`. |
| Geofence → recipients | ✅ Exists | **Reuse** `AgencyAuthorizationService.recipients(group, since)` + `UserGeoService.findWithinRadiusMiles` (bbox + Haversine, 30-day recency). |
| Geo math | ✅ Exists | **Reuse** `GeoUtil.haversineKm/around/milesToKm/validLatLng`. |
| Blast idempotency idiom | ✅ Exists | **Clone** `AlertPost` unique-index + `AgencyAlert.dedup_key` (`saveAndFlush` + catch `DataIntegrityViolationException`). |
| `sms_allowance_monthly` **enforcement** | ❌ Gap — column exists (`Group.smsAllowanceMonthly`, V38) but is **display-only**, never written or decremented. | **Build** the atomic usage ledger, fail-closed. |
| Twilio transport | ❌ Gap — no dependency, no code. | **Build** `TwilioSmsSender`, dormant-when-unconfigured (Stripe/Sentry pattern). |
| Durable queue / retry / DLQ | ❌ Gap — CloudAMQP/RabbitMQ provisioned but **100% unwired** (no `@RabbitListener`/`RabbitTemplate`); `@Async` pool is non-durable. | **Build** the `sms_outbox` + drain worker. |
| Phone / SMS consent | ❌ Gap — `UserInfo.phone` is free-form, no E.164, no opt-in. | **Build** the consent model (TCPA/A2P — §6). |
| Rate limiter for quota | ⚠️ `RateLimiterService` is **in-memory, per-pod, fail-OPEN** — structurally unfit for a billing quota (silently over-sends after any restart). | **Build** DB-atomic, fail-CLOSED. |

Key data facts: alerts carry raw **GeoJSON geometry** only (NWS polygon, USGS
point, FEMA `null`) — no zips/UGC. Agency geofence = point + radius (≤50 mi)
and/or a legacy zip set, gated by `agency_authorized`. `NotificationService`
(`sendHazardAlertBatch`) is **FCM-only**. `SchedulingConfig` pool = **3 threads**
for ~15 jobs (bump to add workers).

---

## 3. Intersection logic (alert geometry ∩ agency geofences)

**Invert the direction.** Recipient resolution today is *agency → users*; the
engine needs *alert → agencies → users*. This is cheap because the authorized-
agency set is small (`findAuthorizedAgencies()` → tens–low-hundreds), so a
per-tick `agencies × active-alerts` loop is trivial after a bbox pre-filter.

**New worker `AgencySmsMatchService`** (`@Scheduled`, ~PT2M, staggered
`initialDelay`; clone `AlertDispatchService`):

1. **Load candidates once/tick:** `findAuthorizedAgencies()` (`agency_authorized = true`, has geo). Hold in memory (small set).
2. **Severity gate first** (SMS is expensive + TCPA-sensitive): only alerts over a hard threshold — reuse `isLifeThreatening()` (NWS Severe/Extreme, USGS mag ≥ 6) — proceed.
3. **Per (alert, agency) geo test** — upgrade beyond the current first-vertex coarseness because false matches cost real money:
   - Cheap **bbox pre-filter** via `GeoUtil.around(agencyLat, agencyLng, radius)` vs. alert geometry bbox.
   - **Precise test by alert type:**
     - USGS **point** → `haversineKm(agencyCenter, quakePoint) ≤ radius`.
     - NWS **polygon** → circle-vs-polygon: center inside polygon (ray-cast point-in-polygon) **OR** any polygon vertex within radius **OR** min segment-to-center distance ≤ radius. Recommend adding **JTS** (`org.locationtech.jts:jts-core`, lightweight on the 512 MB dyno) for a robust polygon/circle intersection, falling back to vertex/center tests.
     - **Zone-only / FEMA (`geometry = null`)** → **v1: skip** (radius/polygon only — see Decisions). UGC/SAME → zip mapping is deferred.
4. **Dedup (alert × agency):** `agency_sms_dispatch` with `UNIQUE(alert_id, group_id)` — clone the `AlertPost` guard so an alert never re-fires the same agency across ticks while active; `resolved_at` mirrors `resolveOnce`.
5. **Resolve recipients within the matched agency:** `AgencyAuthorizationService.recipients(agency, now − 30d)` **∪ explicitly opted-in members of the agency group**, then **filter to SMS-eligible** (`phone_verified && sms_opt_in && sms_stop_at IS NULL && phone_e164 present`). (This eligibility filter does not exist today.)
6. **Cross-agency per-recipient dedup:** one text per alert per phone regardless of how many geofences cover the user — enforced by `sms_outbox UNIQUE(alert_id, phone_e164)`.

**Efficiency:** O(agencies × alerts) with bbox short-circuit; recipient queries
are already index-backed (`idx_user_info_geo`). Radius mode needs no new spatial
infra; polygon mode adds JTS only.

---

## 4. Rate limiting & billing (strict `sms_allowance_monthly`, fail-closed)

**Do NOT reuse `RateLimiterService`** — in-memory, per-pod, resets on restart,
fail-open. A billing quota must be **DB-atomic and fail-closed**.

**Ledger table `agency_sms_usage`** — period-keyed so the monthly reset is
implicit (new month = new row, no reset cron):

```
agency_sms_usage(
  id BIGSERIAL PK,
  group_id VARCHAR NOT NULL,
  usage_month VARCHAR(7) NOT NULL,     -- 'YYYY-MM' (UTC)
  sent_count INT NOT NULL DEFAULT 0,
  allowance_snapshot INT,              -- allowance in effect (audit)
  created_at/updated_at TIMESTAMPTZ,
  UNIQUE(group_id, usage_month)
)
```

**Atomic reserve at ENQUEUE time** (reserve *before* inserting into the outbox,
so we never enqueue past the cap). Kept **off** the `groups` row to avoid
`Group`'s `@Version` optimistic-lock (409 `STALE_WRITE`) contention.

```sql
-- ensure the period row exists (idempotent)
INSERT INTO agency_sms_usage(group_id, usage_month, allowance_snapshot)
VALUES (:g, :m, :allowance) ON CONFLICT (group_id, usage_month) DO NOTHING;

-- fail-CLOSED conditional increment: 0 rows affected = would exceed cap
UPDATE agency_sms_usage
   SET sent_count = sent_count + :n, updated_at = now()
 WHERE group_id = :g AND usage_month = :m
   AND sent_count + :n <= :allowance;
```

For a batch of `R` eligible recipients, compute `remaining = allowance − sent_count`
and reserve `granted = max(0, min(R, remaining))`; enqueue exactly `granted`
rows and **log the truncation** (which recipients were dropped at the cap; optionally
notify the agency admin "SMS cap reached for this alert"). Prefer a **silent cap +
audit** over a 409 for an automated worker. Keep a **global per-tick cap** too
(à la `MAX_PUSH_RECIPIENTS = 500`) to protect the dyno independent of per-agency limits.

**Reserve ↔ actual-send reconciliation (APPROVED policy):**
- **Refund** the reserve only for failures **before Twilio accepts** — locally-invalid E.164, Twilio 4xx rejection, or a dedup collision (fewer rows inserted than reserved).
- **No refund once Twilio accepts** (`queued`/`accepted`): if Twilio bills us, the agency's allowance is docked. Carrier-side `undelivered` is **not** refunded.

**Allowance source:** the column is never populated by code today (setter has 0
callers). Add **per-tier defaults** (extend `PlanTier` with `smsAllowance()` or set
it in the Stripe webhook alongside `maxSeats`) and resolve the cap through
`BillingService.accountStatus().effectiveTier` so **billing overrides** are honored;
`allowance_snapshot` records what applied that month. Month key is **UTC**.

---

## 5. Queueing / resilience (mass-event safe, nothing dropped)

**Recommendation: DB-backed outbox + `@Scheduled` drain — not RabbitMQ, not `@Async`.**
- **RabbitMQ/CloudAMQP** has zero scaffolding; a listener container + broker connection is memory the **512 MB / SerialGC dyno (documented R14 history)** can't spare.
- **`@Async`** (max 8, `CallerRunsPolicy`, queue-100) is **non-durable** — a restart drops the buffer and `CallerRunsPolicy` blocks the caller. Unacceptable for money-metered, must-not-drop SMS.
- **Outbox** is durable across R14 kills, retriable, back-pressure-bounded, and matches every existing pattern (`AlertPost`, `idempotency_keys`).

**Queue table `sms_outbox`:**
```
sms_outbox(
  id BIGSERIAL PK,
  group_id VARCHAR NOT NULL,           -- charged agency (attribution)
  alert_id VARCHAR NOT NULL,
  recipient_email VARCHAR,
  phone_e164 VARCHAR NOT NULL,
  body VARCHAR(1600) NOT NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'NEW',   -- NEW|SENDING|SENT|FAILED|DEAD
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_at TIMESTAMPTZ,
  twilio_sid VARCHAR, twilio_status VARCHAR, error_code VARCHAR, error_message VARCHAR,
  created_at/updated_at TIMESTAMPTZ,
  UNIQUE(alert_id, phone_e164)          -- cross-agency per-recipient dedup
)
-- partial index for the claim query:
CREATE INDEX idx_sms_outbox_claimable ON sms_outbox (next_attempt_at) WHERE status IN ('NEW','FAILED');
```

**Enqueue (MATCH worker, transactional):** after the billing reserve, bulk-insert
`NEW` rows with `ON CONFLICT (alert_id, phone_e164) DO NOTHING`; reconcile the
reserve against rows actually inserted (refund dedup collisions).

**Drain worker (`@Scheduled` ~PT15–30S, batch-bounded):**
```sql
SELECT * FROM sms_outbox
 WHERE status IN ('NEW','FAILED') AND next_attempt_at <= now()
 ORDER BY created_at
 FOR UPDATE SKIP LOCKED
 LIMIT :batch;                          -- e.g. 100–200/tick
```
- Mark `SENDING` (`claimed_at = now()`); send via a Twilio **Messaging Service SID** (Twilio owns number-pool selection + per-number pacing → offloads throughput).
- **Success** → `SENT` + `twilio_sid`. **Transient** (5xx/429/timeout) → `FAILED`, `attempts++`, `next_attempt_at = now() + backoff(attempts)` (exponential + jitter, capped). **Permanent** (invalid number 4xx) → `DEAD` + **refund**. **Max attempts** → `DEAD` — this terminal state **is** the dead-letter queue (queryable, alertable); no separate DLQ infra needed.
- **`FOR UPDATE SKIP LOCKED`** keeps it safe if a second dyno ever runs the drain (no double-send). A `SENDING` row with stale `claimed_at` and null `twilio_sid` is re-claimed on recovery; pass a Twilio **idempotency key = `outbox.id`** to prevent dupes on retry-after-timeout.

**Mass weather event = deep outbox, not a crash.** The MATCH worker enqueues
bounded by (allowance + global cap); the DRAIN worker paces at a sustainable MPS.
A 50k-recipient event just makes the outbox deep and drains over minutes — no giant
in-memory list, no thread explosion — and rows survive restarts, so **nothing is
dropped**. Add a simple **Twilio circuit breaker** (global backoff on sustained
failures). **Ops:** bump `SchedulingConfig` poolSize 3 → 5 (match + drain + resolve).
`TwilioSmsSender` is **dormant when unconfigured** (no SID/token → no-op, no crash).
Status-callback endpoint `POST /api/twilio/status` (Twilio-signature-validated)
updates `twilio_status`.

---

## 6. ⚠️ Compliance is a hard gate, not a footnote (TCPA / A2P 10DLC)

US agency SMS **cannot ship** without all of:
- **A2P 10DLC brand + campaign registration** with Twilio — **multi-week lead time**; start early.
- **Explicit prior express opt-in** per recipient (double opt-in recommended). `UserInfo.phone` today is an unverified free-form profile string — sending to it is legally unsafe.
- **STOP / HELP keyword** handling — honor `STOP` (set `sms_stop_at`, suppress all future SMS) and `HELP` per carrier rules (Twilio Advanced Opt-Out or app-side).
- Clear sender identity + opt-out language in message copy; per-message + quiet-hours considerations (could extend the skeletal `PushPolicyService` with an SMS category).

**This gates go-live regardless of engineering readiness.** Consent model
(`user_info.phone_e164`, `phone_verified`, `sms_opt_in`, `sms_opt_in_at`,
`sms_stop_at`) + phone-verification UX + A2P registration should begin in parallel
with any engineering when this leaves the backlog.

---

## 7. Data model summary (proposed migration, deferred)

When implemented, a single Flyway migration adds: `agency_sms_usage`, `sms_outbox`,
`agency_sms_dispatch` (all `BIGSERIAL` PK + the unique constraints above), and the
five `user_info` consent columns. `pom.xml` adds `com.twilio.sdk:twilio` (+ optional
`org.locationtech.jts:jts-core`). Config vars: `TWILIO_ACCOUNT_SID`,
`TWILIO_AUTH_TOKEN`, `TWILIO_MESSAGING_SERVICE_SID`, `TWILIO_STATUS_CALLBACK_URL`.
`Instant` fields map to `TIMESTAMPTZ` to satisfy `ddl-auto=validate`.

---

## 8. Approved decisions (locked)

| Question | Decision |
|---|---|
| Overlapping-agency billing | **First-match by group id**; record the attribution on the outbox/dispatch row. |
| Failure refunds | **Pre-accept only.** Once Twilio bills us, the agency's allowance is docked. |
| Zone-only / FEMA alerts | **v1 = radius/polygon only.** Skip UGC/SAME → zip mapping for now. |
| Allowance tz / reset | **UTC** for `usage_month` (`'YYYY-MM'`). |
| Recipients | Users within the geofence **AND** explicitly opted-in members of the agency group — both **only if phone-verified**. |

---

## 9. Suggested phasing (when pulled off backlog)

0. **Compliance + consent** — A2P 10DLC registration, phone-verify UX, opt-in/STOP. Long lead; start first.
1. **Foundation** — migration (usage ledger + outbox + dispatch + consent cols) + `TwilioSmsSender` (dormant) + drain worker behind a flag, no billing.
2. **Match** — `AgencySmsMatchService` (alert × agency, severity gate, dedup).
3. **Billing** — usage ledger + atomic reserve + fail-closed + reconciliation + per-tier allowance population.
4. **Reconcile + operate** — status callbacks, admin usage dashboard, circuit breaker, alerting on `DEAD` rows.

---

## 10. Source references (audited)

`AlertIngestService` (NWS/USGS/FEMA poll, snapshot, `getSnapshotForPoint`,
`firstCoord`, `NormalizedAlert`) · `AlertDispatchService` (`dispatchOnce`,
`pushSevereAlert`, `isLifeThreatening`, `AlertPost` dedup) · `AgencyAlertService`
(manual geofence blast + `AgencyAlert.dedup_key`) · `AgencyAuthorizationService`
(`recipients`, `hasGeo`, `MAX_RADIUS_MILES=50`) · `UserGeoService.findWithinRadiusMiles`
· `GeoUtil` · `GroupRepo.findAuthorizedAgencies` / `findByJurisdictionZip` ·
`Group` (`jurisdictionLat/Lng/RadiusMiles`, `agencyAuthorized`, `jurisdictionZips`,
`smsAllowanceMonthly`) · `NotificationService.sendHazardAlertBatch` (FCM only) ·
`PushPolicyService` / `RateLimiterService` (in-memory, fail-open) · `NotificationLog`
· `BillingService` / `PlanTier` / `PlanCapability` (soft, unenforced) ·
`GroupService.assertSeatAvailable` (the one hard billing gate to mirror) ·
`SchedulingConfig` (pool=3) · `AsyncConfig` (max 8) · CloudAMQP (provisioned, unwired).
