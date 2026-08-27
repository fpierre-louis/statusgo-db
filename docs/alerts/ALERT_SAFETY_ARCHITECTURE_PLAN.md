# Alert Safety Architecture Plan

Date: 2026-08-27

## Current Architecture

Alert ingestion lives in `AlertIngestService`. It polls NWS active alerts, USGS recent earthquakes, and FEMA disaster declarations into an in-memory `Snapshot` of `NormalizedAlert` records. The NWS normalizer preserves product `event`, `severity`, `urgency`, `certainty`, `messageType`, `status`, single `response`, official `headline`, `description`, `instruction`, `areaDesc`, effective/expiry times, GeoJSON geometry, UGC/SAME zone codes, and CAP `references`.

Alert rendering uses `AlertFeedService` and `AlertResource`. `GET /api/alerts/feed` returns `AlertFeedResponse`, whose cards intentionally separate SitPrep-authored copy (`headline`, `whatToDo`, `precautions`) from official issuer text (`official`). The frontend consumes this through `Status Now/src/shared/alerts/emergencyApis.js`, `/hazards` (`FemaWeatherMVP.js`), and the map (`src/map/MapView.js` plus `InfoDrawer.js`).

Dispatch lives in `AlertDispatchService`. It loads `src/main/resources/templates/alert-dispatch-templates.json`, matches templates by exact NWS product name, FEMA incident type substring, or USGS magnitude floor, creates `alert-update` community posts, dedupes via `AlertPost`, and sends nearby hazard pushes through `NotificationService` after `PushPolicyService` checks user hazard preferences.

## Current Decision Flow

- Template match: first `DispatchTemplate.matchesAlert` match in JSON declaration order.
- Warning/watch: `DispatchTemplate.tier` remains a legacy visual/copy grouping.
- Push eligibility: `AlertSafetyPolicy` reads lifecycle state, template match,
  `sitprep.dispatchMode`, compatibility metadata, and the warning-shape
  cross-check where needed.
- Feed eligibility: lifecycle-blocked alerts are suppressed by `AlertSafetyPolicy`;
  matched alerts keep official text, while SitPrep copy renders only when the
  policy allows SitPrep guidance.
- Hazard type: template `hazardType`, normalized through `HazardType` when writing post tags.
- Official text: `NormalizedAlert.headline`, `description`, and `instruction`, preserved in `AlertCardDto.Official`.
- SitPrep steps: template `body` and `steps`, with slot sanitization.
- Cancellation/update handling: non-`Actual` NWS rows are dropped at ingest; `AllClear` and `Past` are blocked in dispatch/push; lifecycle state is derived for cards.
- Deduplication: one `AlertPost` per `(source-id, geocellId)`.

## Confirmed Safety Risks

- `response` is modeled as one string, while CAP response type can be absent, single, multiple, or unknown.
- NWS `properties.parameters` are carried in `NormalizedAlert.parameters`;
  policy currently trusts only the documented high-impact parameter names
  listed below. CAP severity, urgency, certainty, response types, event codes,
  scope, sender, and source-system identity remain available as separate
  normalized fields.
- Active Situation still needs to consume movement directives instead of reading
  `protectiveAction` as if every shelter-like safety behavior were an official
  shelter-in-place order.
- USGS earthquake handling is magnitude-only; impact metadata such as PAGER/MMI is not normalized.
- Frontend active-situation surfaces consume active group alerts, external hazard feeds, plan activation state, and map POIs without one shared presentation contract.

## Proposed Architecture

Create an explicit safety-policy layer:

`CAP/provider normalization -> NormalizedAlert -> AlertSafetyPolicy -> template compatibility -> dispatch/feed decision -> FE presentation`.

The policy should return structured `AlertSafetyDecision` fields: lifecycle eligibility, `DispatchMode`, `GuidanceMode`, normalized protective actions, movement directive, compatibility result, and diagnostic reason. Official issuer wording remains available in every non-suppressed card. SitPrep guidance renders only when the template is compatible and safety-approved. Unknown or conflicting template/issuer semantics degrade to official-only, never generic fallback copy.

`tier` may remain for visual treatment and backward compatibility, but it must stop being the authoritative interruptiveness field once `sitprep.dispatchMode` exists on templates and policy decisions.

## Pass 2 Template Certification State

`alert-dispatch-templates.json` now carries the production schema documented in
`ALERT_TEMPLATE_SCHEMA.md`: protective action, compatible and incompatible CAP
response types, explicit SitPrep dispatch/guidance modes, evidence metadata, and
safety-review metadata.

The Pass 2 review deliberately stops at `source_verified` or `blocked`.
Production rendering still requires `safetyReview.status = approved`, so
source-verified templates continue to degrade to official issuer wording until a
human reviewer approves them.

Known unsafe groupings corrected in the production template file:

- `Tornado Warning` split from `Extreme Wind Warning`.
- `Tropical Storm Warning` split from non-tropical `Storm Warning`.
- `Snow Squall Warning` split from generic winter-storm copy.
- `Tsunami Advisory` split from `Tsunami Watch`.
- `Air Quality Alert` split from `Dense Smoke Advisory`.
- `Flash Flood Statement` split from `Flash Flood Warning`.
- `Flood Statement` split from `Flood Warning`.
- Civil, law-enforcement, hazardous-materials, nuclear, and radiological products split into individual rows.

`askTag` is a behavioral route into FE Ask-guide content, not a badge. Tornado
templates previously pointed at `Hurricane`; Pass 2 removes that mapping because
there is no confirmed tornado Ask guide in the current FE taxonomy. Generic Air
Quality Alert also leaves `askTag` null so it cannot silently borrow Wildfire
guidance.

## Pass 2B Safety Corrections

Pass 2B adds `sitprep.movementDirective` and exposes it through
`AlertSafetyPolicy.Decision` / `AlertCardDto.Safety`. `protectiveAction` remains
the SitPrep guidance action; `movementDirective` is the narrower official
movement claim. General indoor-safety guidance, smoke/heat exposure reduction,
and earthquake Drop/Cover/Hold return `none`, while `Evacuation Immediate`
returns `evacuate` and `Shelter In Place Warning` returns `shelter_in_place`.
Ambiguous official-only civil/hazmat/nuclear/radiological rows return
`follow_official_instruction`.

Pass 2B also adds `sitprep.impactAware`. Severe Thunderstorm Warning, Flash
Flood Warning, Flood Warning, and Snow Squall Warning now default to
`attention`.

Pass 2C normalizes real NWS `properties.parameters` and keeps the final
escalation decision in `AlertSafetyPolicy`. Current trusted impact fields are:
`thunderstormDamageThreat=CONSIDERABLE|DESTRUCTIVE`,
`flashFloodDamageThreat=CONSIDERABLE|CATASTROPHIC`,
`snowSquallImpact=SIGNIFICANT`, and `WEAHandling=WEA`. Flood Warning remains
lower/default because the inspected live NWS feed exposed fields such as VTEC,
NWSheadline, BLOCKCHANNEL, and eventEndingTime, but no trustworthy structured
damage-threat signal equivalent to Flash Flood Warning. CAP severity,
urgency/certainty, and response type alone no longer escalate an impact-aware
weather warning.

The backend still does not normalize PAGER, MMI, or ShakeMap, so USGS
magnitude-only earthquake alerts remain attention-mode.

Current NWS event-name verification is documented in
`NWS_EVENT_CATALOG_AUDIT.md`; the 2026-08-27 official alert-types response is
saved as an offline test fixture.

## Auditability Snapshot Finding

Can historical alerts be reinterpreted differently after policy/template
changes?

PARTIALLY.

The dispatched community post snapshots a title and description in `Post` at the
time the dispatch tick creates it. `AlertPost` then stores only tracking fields:
`alertId`, `hazardType`, `geocellId`, `postId`, `createdAt`, `expiresAt`, and
`resolvedAt`. It does not persist `protectiveAction`, `dispatchMode`,
`guidanceMode`, compatibility, `policyVersion`, `templateVersion`, evidence
version, or the complete `AlertSafetyPolicy.Decision`.

The live alert feed is different: `AlertFeedService` recomputes
`AlertSafetyPolicy.evaluate(...)` from the current in-memory alert snapshot and
the current template file each time the feed card is assembled. That means a
currently active alert can display a different structured safety interpretation
after a template or policy change, even though an already-created post body will
not rewrite itself.

Recommended follow-up: add a narrow safety-decision snapshot to dispatched alert
records before production approval is enabled. Minimum fields:
`templateKey`, `templateVersion`, `safetyReview.status`, `policyVersion`,
`protectiveAction`, `dispatchMode`, `guidanceMode`, `compatibility`,
`capActions`, `decisionReason`, and evidence URLs or evidence version. Do not
retrofit this pass with a broad migration; it belongs with the approval/dispatch
activation gate.
