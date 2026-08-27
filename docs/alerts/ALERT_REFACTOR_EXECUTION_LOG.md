# Alert Refactor Execution Log

Date: 2026-08-27

## Repository State

- Frontend repo: `Status Now`, branch `sitprep-features`, dirty with active map/pin/probe work from the map-view epic.
- Backend repo: `sitprepapi 2`, branch `main`, dirty in the separate `UserInfoService` idempotency lane.
- Do not use broad staging commands. Stage only explicit files for this epic.

## Actions

- Read master prompt and handoff 3 design docs.
- Audited `AlertIngestService`, `AlertDispatchService`, `AlertFeedService`, `AlertCardDto`, alert tests, `PlanActivationService`, `PlanActivation`, `PlanActivationDtos`, `ResourceListingService`, `MapPoiDto`, and frontend alert/map consumers.
- Counted current template objects and safety metadata gaps.

## Wording Changes

None in the production template file yet. That is intentional for this pass: current
templates do not carry reviewed provenance or compatibility metadata, so the code
now suppresses SitPrep-authored guidance instead of editing copy in place and
pretending it has been reviewed.

## Code Changes

- Added `AlertSafetyPolicy`, the shared backend decision point for lifecycle,
  dispatch mode, guidance mode, and CAP response/template compatibility.
- Extended `AlertIngestService.NormalizedAlert` to preserve CAP-adjacent fields:
  sender, sent, scope, event codes, response types, unknown response types, and
  source system.
- Changed NWS response parsing from a single loose string to collection semantics.
  Unknown response values are preserved separately.
- Changed `AlertDispatchService` so critical push, post body, and task copy go
  through the safety decision. Unreviewed or incompatible templates fall back to
  official issuer wording or no SitPrep guidance.
- Added template metadata parsing for protective action, compatible/incompatible
  response types, SitPrep guidance mode, evidence, and safety review status.
- Changed `AlertFeedService` so feed cards expose safety metadata and do not
  populate SitPrep headline/precautions when policy says `official_only` or
  `no_guidance`.
- Extended `AlertCardDto` with a `safety` contract carrying dispatch mode,
  guidance mode, compatibility, protective actions, and reason.
- Updated frontend alert enrichment to honor backend safety mode and avoid
  resurrecting local fallback guidance when the feed says official-only.

## Implementation Boundary

This is a safety-policy and presentation-contract slice, not the full active
situation epic. Pass 2 fills the production template JSON with source evidence,
compatibility metadata, and safety-review records, but it deliberately stops at
`source_verified` or `blocked`. Until human approval lands, current templates are
treated as unapproved: official issuer wording remains available, but
SitPrep-authored guidance is withheld.

Active-plan ecosystem, meeting-place/check-in semantics, and resource-distribution
planning were audited and documented, but their runtime DTO/migration changes
were not implemented in this pass.

## Verification

- Backend targeted suite:
  `./mvnw test -Dtest=AlertSafetyPolicyTest,AlertFeedServiceTest,AlertWatchWarningTierTest,HazardPushPolicyTest,AlertTemplateCoverageTest,AlertBodySlotTest`
  passed with 94 tests, 0 failures, 0 errors.
- Backend full suite:
  `./mvnw test` passed with 563 tests, 0 failures, 0 errors. The sandboxed run
  failed earlier because embedded Tomcat could not bind a local test port; the
  same suite passed outside that sandbox.
- Frontend focused alert-enrichment suite:
  `npm test -- src/shared/alerts/alertFeedEnrichment.test.js` passed with 3
  tests.
- Frontend full test suite:
  `npm test` passed with 20 files and 266 tests.
- Frontend lint:
  `npm run lint` passed.
- Frontend build:
  `npm run build` passed, including the public-leak and viewport checks.

## Remaining Release Gates

- Human-review and explicitly approve source-verified templates before any
  SitPrep-authored guidance is approved for production display.
- Resolve the four blocked civil/law-enforcement templates with issuer- or
  jurisdiction-specific review.
- Persist safety decision snapshots if historical alert presentation must remain
  audit-stable after template/policy changes.
- Add runtime active-situation contracts for household/group/resource semantics.
- Add frontend visual regression coverage for the safety drawer/map preview
  states once the UI consumes the new safety contract directly.

## Pass 2 Template-Specific Changelog

All production templates were reopened against the real
`alert-dispatch-templates.json` schema. Full current headline, body, steps,
evidence URLs, compatibility, and human-review checkboxes are in
`ALERT_TEMPLATE_HUMAN_REVIEW_MATRIX.md`; this log records the old-to-new safety
rationale for each production template row.

| Event / template | Old wording or grouping | New wording | Reason | Protective action | Evidence / result | Remaining risk |
| --- | --- | --- | --- | --- | --- | --- |
| Tornado Warning | Shared `Tornado Warning \| Extreme Wind Warning`; body said `Tornado spotted...` and Ask tag was `Hurricane`. | `A tornado warning is in effect...` | Avoids claiming visual confirmation and removes wrong Ask route. | SHELTER | weather.gov tornado; source_verified | Mobile/outdoor edge guidance needs human review. |
| Extreme Wind Warning | Inherited tornado warning copy. | Dedicated extreme wind shelter copy. | Wind warning is not a tornado warning. | SHELTER | weather.gov wind; source_verified | Local wind-product wording can vary. |
| Severe Thunderstorm Warning | Said damaging wind and hail were moving in; told users to unplug during active storm. | Severe-storm warning copy with inside/away-from-windows/no-travel steps. | Avoids unsupported hail+wind certainty and active-storm unplug advice. | SHELTER | weather.gov thunderstorm; source_verified | Destructive-tag policy is not modeled. |
| Flash Flood Warning | Shared with Flash Flood Statement. | Imminent/happening flood action copy. | Warning stays act-now; statement no longer borrows it. | AVOID | weather.gov flood/TADD; source_verified | Evacuation orders remain official-specific. |
| Flash Flood Statement | Shared with Flash Flood Warning. | Official-update language. | Statement may update, extend, or cancel details. | MONITOR | weather.gov flood/TADD; source_verified | Active warning context may still be nearby. |
| Flood Warning | Shared with Flood Statement. | Happening/expected flood copy. | Keeps warning action separate from update statements. | AVOID | weather.gov flood/TADD; source_verified | River-flood lead times vary. |
| Flood Statement | Shared with Flood Warning. | Official-update language. | Avoids act-now wording on every statement. | MONITOR | weather.gov flood/TADD; source_verified | Statement semantics vary by office. |
| Hurricane Warning / Typhoon Warning | `A hurricane is on the way...look at your evacuation plan now.` | Conditions expected; evacuate only if officials say so. | SitPrep must not independently order evacuation. | PREPARE | weather.gov hurricane + Ready.gov evacuation; source_verified | Surge/wind zones are not normalized. |
| Tropical Storm Warning | Shared with non-tropical Storm Warning. | Tropical-storm prep/avoid-travel copy. | Tropical cyclone wording must not cover marine storm warning. | PREPARE | weather.gov hurricane/wind; source_verified | Flood/evacuation details stay official-only. |
| Storm Warning | Shared tropical-storm wording. | Non-tropical storm-force wind copy, official-only. | Product is commonly marine/non-tropical. | AVOID | weather.gov wind products; source_verified | Household relevance needs filtering. |
| Blizzard Warning | Stay off roads and keep heat/food/water nearby. | Source-backed stay-put/heat/water/food wording. | Retained but tightened around official travel needs. | SHELTER | weather.gov winter; source_verified | Road-closure details are local. |
| Winter Storm / Ice Storm / Lake Effect Snow Warning | Included Snow Squall Warning. | Broader snow/ice delay-travel copy. | Snow squall is short-fused and travel-specific. | AVOID | weather.gov winter; source_verified | Ice outage copy needs reviewer scrutiny. |
| Snow Squall Warning | Borrowed generic winter storm copy. | Dedicated sudden-road-danger copy. | Short-fused road hazard needs its own handling. | AVOID | weather.gov snow squall; source_verified | Only relevant if user may travel. |
| Extreme Cold Warning | Cold injury copy with pipe drip. | Stay-inside/cover-skin/check-heat copy. | Pipe behavior moved out of warning action. | SHELTER | weather.gov cold; source_verified | Building-specific pipe advice varies. |
| Extreme Heat Warning | Dangerous heat copy. | Cool-place/water/avoid-work copy. | Source-backed and kept short. | SHELTER | weather.gov heat; source_verified | Cooling-center data not integrated. |
| Red Flag Warning / Extreme Fire Danger | Could read like active fire; included go-bag. | Critical fire-weather, avoid sparks, monitor officials. | Fire weather is not an active fire. | AVOID | weather.gov wildfire; source_verified | Local burn restrictions vary. |
| Fire Warning | Active fire copy told users to be ready to leave. | Official-instruction-first, official-only. | Fire Warning semantics vary; evacuation cannot be inferred. | HAZARD_SPECIFIC | weather.gov wildfire + Ready.gov evacuation; source_verified | Needs human review before action copy. |
| High Wind Warning | Bring loose things inside, avoid downed lines. | Get inside, avoid trees/lines, drive carefully. | Source-backed and more direct. | SHELTER | weather.gov wind; source_verified | Local thresholds vary. |
| Dust Storm / Blowing Dust Warning | Pull off, lights off, foot off brake. | Same action, shorter and source-backed. | Keeps NWS road-safety behavior. | AVOID | weather.gov wind/dust; source_verified | No full alternate-if-trapped flow. |
| Avalanche Warning | Included backcountry travel and equipment-adjacent copy. | Avoid avalanche terrain and runouts. | Equipment copy belongs in watch/pre-trip context. | AVOID | weather.gov avalanche; source_verified | Local avalanche center is operational authority. |
| Tsunami Warning | Leave beach/low ground now. | Move high/inland if in hazard zone; stay away. | Avoids implying every viewer is in a coastal zone. | EVACUATE | weather.gov tsunami; source_verified | Zone membership not normalized. |
| Volcano Warning | Hard-coded stay-inside and evacuation step. | Official-first; ash guidance conditional. | Volcano products can require different actions. | HAZARD_SPECIFIC | weather.gov ash + USGS ash; source_verified | Official-only until hazard subtype exists. |
| Earthquake Warning | Drop, cover, hold on. | Active-shaking DCHO plus after-shaking hazard check. | Keeps context correct for warning. | SHELTER | USGS earthquake; source_verified | Provider-specific warning semantics are rare. |
| Air Quality Alert | Shared smoke/N95 copy and Wildfire Ask tag. | Broad air-quality copy, official-only. | Feed lacks pollutant/smoke context. | AVOID | AirNow AQI; source_verified | Cannot approve smoke-specific action generically. |
| Dense Smoke Advisory | Shared generic air-quality row. | Dedicated smoke/recirculate/N95 copy. | Smoke-specific guidance only on smoke-specific product. | SHELTER | EPA smoke; source_verified | Heat plus smoke can change best action. |
| Evacuation Immediate | Leave-now copy. | Official route/order language. | Official evacuation instruction is controlling. | EVACUATE | Ready.gov evacuation; source_verified | Local route/order details are decisive. |
| Shelter In Place Warning | Detailed sealing/fan/AC advice. | Shelter, close doors/windows, follow official ventilation details. | Shelter-in-place subtype matters. | SHELTER | Ready.gov shelter; source_verified | Chemical/weather/law variants differ. |
| Civil Danger Warning | Grouped with local/civil/law products. | Official-only danger warning. | No single safe protective action. | HAZARD_SPECIFIC | blocked | Jurisdiction-specific review required. |
| Local Area Emergency | Grouped with civil/law products. | Official-only local emergency. | Relay format can mean many actions. | HAZARD_SPECIFIC | blocked | Local semantics required. |
| Civil Emergency Message | Grouped with civil/law products. | Official-only civil message. | Message may be informational or action-oriented. | HAZARD_SPECIFIC | blocked | Needs issuer taxonomy. |
| Law Enforcement Warning | Grouped with civil products. | Official-only law-enforcement warning. | Generic movement/shelter advice may conflict. | HAZARD_SPECIFIC | blocked | Legal/law-enforcement review needed. |
| Hazardous Materials Warning | Grouped with nuclear/radiological as shelter copy. | Official-first hazmat; shelter/evacuate conditional. | Plume action can be shelter or evacuation. | HAZARD_SPECIFIC | CDC chemical; source_verified | Incident-specific plume data absent. |
| Nuclear Power Plant Warning | Grouped as chemical/radiation shelter copy. | Official-first nuclear-plant warning. | Action depends on plant/distance/release. | HAZARD_SPECIFIC | EPA radiation; source_verified | Zone and release type absent. |
| Radiological Hazard Warning | Grouped as chemical/radiation shelter copy. | Official-first radiological warning. | May require decontamination, shelter, or evacuation. | HAZARD_SPECIFIC | EPA radiation; source_verified | Official instructions remain controlling. |
| Tornado Watch | Used Hurricane Ask tag. | Tornado preparedness copy, no Ask tag. | No tornado Ask guide exists today. | PREPARE | weather.gov tornado; source_verified | Ask taxonomy follow-up. |
| Severe Thunderstorm Watch | Strong storm prep copy. | Source-backed inside/charge/loose-items prep. | Watch remains prepare-only. | PREPARE | weather.gov thunderstorm; source_verified | Watch geography can be broad. |
| Flash Flood Watch / Flood Watch | Flood possible copy. | Route-to-high-ground preparedness. | Keeps possible/prep language. | PREPARE | weather.gov flood; source_verified | Local lead times vary. |
| Hurricane / Typhoon / Tropical Storm Watch | Included Storm Watch. | Tropical cyclone watch only. | Storm Watch is non-tropical/marine. | PREPARE | weather.gov hurricane + Ready.gov evacuation; source_verified | Evacuation zones not normalized. |
| Storm Watch | Borrowed hurricane watch copy. | Non-tropical storm-force wind watch, official-only. | Avoids tropical assumptions. | PREPARE | weather.gov wind products; source_verified | Often marine-focused. |
| Winter Storm Watch | Winter possible copy. | Source-backed stock/charge/stay-put prep. | Watch remains prepare-only. | PREPARE | weather.gov winter; source_verified | Impact subtype varies. |
| Extreme Cold / Freeze Watch / Freeze Warning | Hard freeze coming copy. | People/pets/pipes/plants prep. | Explicitly prepare-mode despite `Warning` suffix on Freeze Warning. | PREPARE | weather.gov cold; source_verified | Combined cold/freeze row needs review. |
| Extreme Heat Watch | Dangerous heat coming. | Cool-place and hottest-hours prep. | Watch remains prepare-only. | PREPARE | weather.gov heat; source_verified | Vulnerability/cooling center data absent. |
| Fire Weather Watch | Fire-weather prep copy. | Avoid sparks and review evacuation plan. | Watch is conditions, not active fire. | PREPARE | weather.gov wildfire; source_verified | Local burn rules vary. |
| High Wind Watch | Strong winds possible. | Secure items, avoid exposed work, charge devices. | Watch remains prepare-only. | PREPARE | weather.gov wind; source_verified | Thresholds vary. |
| Avalanche Watch | Included equipment guidance. | Pre-trip forecast/equipment context. | Equipment guidance belongs before travel. | PREPARE | weather.gov avalanche; source_verified | Local center is final authority. |
| Tsunami Watch | Shared with Tsunami Advisory. | Preparedness/update language. | Watch is potential future threat. | PREPARE | weather.gov tsunami; source_verified | Zone membership absent. |
| Tsunami Advisory | Shared with Tsunami Watch. | Stay out of water/off beaches. | Advisory is not identical to watch. | AVOID | weather.gov tsunami; source_verified | Local coast/infrastructure rules vary. |
| USGS earthquake magnitude template | Magnitude-only recovery check copy. | Assess people first; DCHO only for aftershocks. | Magnitude alone does not prove local life-threatening impact. | ASSESS | USGS earthquake + PAGER; source_verified | PAGER/MMI not normalized. |
| FEMA hurricane/severe/coastal disaster | Recovery help copy. | Recovery-only official-channel copy. | FEMA declaration is not immediate protective action. | ASSESS | fema.gov IA/areas; source_verified | Eligibility varies by county. |
| FEMA wildfire disaster | Recovery help copy. | Recovery-only official-channel copy. | FEMA declaration is not immediate protective action. | ASSESS | fema.gov IA/areas; source_verified | Eligibility varies by county. |
| FEMA fallback disaster | Recovery help copy. | Official-only fallback recovery copy. | Fallback is too broad for SitPrep-authored detail. | ASSESS | fema.gov IA/areas; source_verified | Incident type may not imply household need. |

## Pass 2 Auditability Finding

Historical safety interpretation is only partially snapshotted. A dispatched
`Post` keeps the title and description that were created at dispatch time, and
`AlertPost` tracks source alert id, hazard type, geocell, post id, creation,
expiry, and resolved timestamps. It does not persist the structured
`AlertSafetyPolicy.Decision`, template version, policy version, evidence
version, protective action, guidance mode, dispatch mode, or compatibility.

Therefore, reopening an already-created post does not rewrite its stored copy,
but a live alert feed/card assembled after a policy or template change can
produce a different structured safety decision for the same upstream alert.
Recommended follow-up: add a safety-decision snapshot before any production
template is marked `approved`.

## Pass 2 Verification

- Template counts:
  - 51 production templates.
  - 47 `source_verified`.
  - 4 `blocked`.
  - 0 `approved`.
- Approval guardrail:
  `rg -n '"status"\s*:\s*"approved"' src/main/resources/templates/alert-dispatch-templates.json docs/alerts src/main/java/io/sitprep/sitprepapi/service src/test/java/io/sitprep/sitprepapi/service`
  returned no matches.
- Backend targeted safety suite:
  `./mvnw test -Dtest=AlertSafetyPolicyTest,AlertFeedServiceTest,AlertWatchWarningTierTest,HazardPushPolicyTest,AlertTemplateCoverageTest,AlertBodySlotTest,AlertPushTargetingTest`
  passed with 111 tests, 0 failures, 0 errors.
- Backend full suite:
  `./mvnw test` passed with 572 tests, 0 failures, 0 errors. The sandboxed run
  still cannot bind the embedded Tomcat test port, so the passing full run was
  executed outside the sandbox and captured to `/tmp/sitprep-pass2-full-backend.log`.
- Diff hygiene:
  `git diff --check` passed.
- Frontend:
  No frontend files were changed in Pass 2, so frontend tests were not rerun.

## Pass 2B Safety Corrections

- Removed basement/lowest-floor guidance from `Extreme Wind Warning`; tornado
  remains the only row using basement/lowest-floor language.
- Added `sitprep.movementDirective` to production templates and surfaced it
  through `AlertSafetyPolicy.Decision` and `AlertCardDto.Safety`.
- Kept general hazard guidance separate from official movement directives:
  ordinary indoor safety returns `none`, `Evacuation Immediate` returns
  `evacuate`, `Shelter In Place Warning` returns `shelter_in_place`, and
  ambiguous official-only rows return `follow_official_instruction`.
- Added `sitprep.impactAware` and lowered default dispatch for Severe
  Thunderstorm Warning, Flash Flood Warning, Flood Warning, and Snow Squall
  Warning to `attention`. The policy escalates them only when CAP response
  types include `Evacuate`/`Execute` or when CAP severity/urgency/certainty
  jointly indicate an extreme immediate/expected observed/likely threat.
- Split `Extreme Cold Watch` from `Freeze Watch` / `Freeze Warning`. Freeze
  Warning remains prepare-mode despite the word `Warning`.
- Changed the USGS magnitude-only template headline from `Earthquake felt
  nearby` to `Earthquake reported nearby`; magnitude-only records remain
  attention-mode until PAGER/MMI/ShakeMap impact fields are normalized.
- Added current NWS event-name verification via
  `src/test/resources/fixtures/nws-alert-types-2026-08-27.json` and
  `NWS_EVENT_CATALOG_AUDIT.md`.
- Added NRC evidence host support intentionally. CDC remains intentionally
  allowed, and lookalike hosts remain rejected.
- Added NWS semantic evidence to the blocked civil/law rows while keeping them
  `blocked`, `official_only`, and not eligible for generic SitPrep action
  guidance.
- Regenerated `ALERT_TEMPLATE_HUMAN_REVIEW_MATRIX.md` from the production
  template JSON and added an automated parity check.

Pass 2B production state:

- 52 production templates.
- 48 `source_verified`.
- 4 `blocked`.
- 0 `approved`.

Pass 2B verification:

- Backend targeted safety suite:
  `./mvnw test -Dtest=AlertSafetyPolicyTest,AlertFeedServiceTest,AlertWatchWarningTierTest,HazardPushPolicyTest,AlertTemplateCoverageTest,AlertBodySlotTest,AlertPushTargetingTest`
  passed with 119 tests, 0 failures, 0 errors.
- Backend full suite:
  `./mvnw test` passed with 580 tests, 0 failures, 0 errors. The passing full
  run was executed outside the sandbox because embedded Tomcat test binding is
  still blocked inside the sandbox.
- Template/review parity:
  52 production templates and 52 human-review matrix entries.
- Approval guardrail:
  No production template is marked `approved`.
- Frontend:
  No frontend files were changed in Pass 2B, so frontend verification was not
  rerun.

Still out of scope: Active Situation/map UI, PAGER/MMI/ShakeMap normalization,
additional NWS impact fields beyond the documented parameter names below,
human approval, and dispatch snapshot persistence.

## Pass 2C Closeout Corrections

- Normalized NWS `properties.parameters` into `NormalizedAlert.parameters`.
- Moved impact-aware escalation off CAP severity/urgency/certainty and response
  type alone. `AlertSafetyPolicy` now trusts only:
  `thunderstormDamageThreat=CONSIDERABLE|DESTRUCTIVE`,
  `flashFloodDamageThreat=CONSIDERABLE|CATASTROPHIC`,
  `snowSquallImpact=SIGNIFICANT`, and `WEAHandling=WEA`.
- Kept Flood Warning lower/default because the inspected live NWS payloads
  exposed VTEC/headline/channel/timing metadata but no trustworthy structured
  flood damage-threat field.
- Added product-specific Extreme Wind Warning provenance from NWS WEA material
  while keeping the corrected no-basement/no-lowest-floor copy.
- Split safety review timestamps into `sourceVerifiedAt` and nullable
  `approvedAt`; production templates remain not human-approved.
- Tightened selected evidence `supports[]` mappings so product/catalog sources,
  PAGER, FEMA declaration pages, and CDC/NRC/EPA references do not overclaim
  support for body or step copy they do not directly substantiate.

Pass 2C production state:

- 52 production templates.
- 48 `source_verified`.
- 4 `blocked`.
- 0 `approved`.
- 84 evidence metadata items.

Pass 2C focused verification:

- `./mvnw test -Dtest=AlertSafetyPolicyTest,AlertFeedServiceTest,AlertTemplateCoverageTest`
  passed with 67 tests, 0 failures, 0 errors.
- `./mvnw test -Dtest=AlertSafetyPolicyTest,AlertFeedServiceTest,AlertWatchWarningTierTest,HazardPushPolicyTest,AlertTemplateCoverageTest,AlertBodySlotTest,AlertPushTargetingTest`
  passed with 120 tests, 0 failures, 0 errors.
- Full backend suite `./mvnw test` passed with 581 tests, 0 failures, 0 errors.
  The run was executed outside the sandbox because embedded Tomcat tests require
  local port binding.
