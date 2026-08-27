# Alert Safety Test Plan

Date: 2026-08-27

## Required Backend Tests

- CAP normalization preserves response types as a collection and preserves unknown raw values without crashing.
- Non-`Actual`, non-public, `Cancel`, `Ack`, `Error`, `AllClear`, `Past`, expired, duplicate, and superseded alerts do not produce ordinary public pushes.
- Watch-shaped CAP data cannot receive warning/critical dispatch even if severity is `Severe`.
- Template guidance is suppressed when compatibility is unknown or incompatible.
- Official issuer `instruction` remains present when SitPrep guidance is suppressed.
- Hazard push notification metadata carries alert identity, lifecycle, source
  parameters, and the safety decision wires used at dispatch time.
- Evidence host allowlist rejects unapproved provenance hosts.
- Evidence host allowlist explicitly accepts intentional CDC/NRC hosts and
  rejects lookalikes such as `cdc.gov.example.com`.
- Safety review status other than `approved` blocks production SitPrep guidance.
- Approved production templates must carry `approvedAt`; blocked templates must
  keep `approvedAt = null`.
- Official movement directives are separate from general hazard safety guidance.
- Impact-aware warning templates do not become critical solely from event name.
- Impact-aware warnings do not become critical from CAP severity,
  urgency/certainty, or response type alone.
- Severe Thunderstorm Warning escalates only when
  `thunderstormDamageThreat` is `CONSIDERABLE` or `DESTRUCTIVE`.
- Flash Flood Warning escalates only when `flashFloodDamageThreat` is
  `CONSIDERABLE` or `CATASTROPHIC`.
- Snow Squall Warning escalates only when `snowSquallImpact=SIGNIFICANT` or
  `WEAHandling=WEA`.
- Flood Warning stays lower/default until the live feed exposes a trustworthy
  structured high-impact field.
- Known template defects stay split once fixed.
- Production templates and the human-review matrix stay in one-to-one parity.
- Plan activation map projections remain audience-safe: public recipient map excludes home/origin and all ack coordinates.
- Check-in status persists even when coordinates are absent or invalid.

## Existing Coverage

Existing tests already cover exact product-name matching, watch-vs-warning shape checks, non-`Actual` NWS ingest filtering, cancellation via `AllClear`/`Past`, push preference toggles, feed DTO separation of official/SitPrep text, null expiry handling, alert-feed radius consistency, plan activation map privacy, and ack coordinate degradation.

## Verification Gate

This epic is not complete until targeted tests and the full backend suite both pass with a successful exit code. Frontend verification should include build/test checks plus a local `localhost:3000` simulator pass for map/alert presentation once backend contracts change.

## 2026-08-27 Tests Implemented

Implemented backend safety regression coverage in `AlertSafetyPolicyTest` for:

- NWS response arrays surviving ingest.
- Unapproved templates withholding SitPrep guidance while preserving critical
  dispatch eligibility when the CAP/product shape still warrants attention.
- Synthetic `source_verified` templates remaining ineligible for SitPrep
  guidance until explicit human approval.
- Evidence host validation rejecting `weather.gov.example.com` style URLs.
- Explicit `sitprep.dispatchMode` outranking legacy `tier` for dispatch policy.
- Non-public, test, cancelled, acknowledged, errored, all-clear, past, and
  expired alerts being blocked from ordinary public guidance.
- Evacuate/shelter incompatibility checks.
- Unknown response values falling back to official-only/no-guidance behavior.
- Product name and severity alone being insufficient to force a critical push.
- Template declared incompatible response types rejecting their own guidance.
- General indoor-safety guidance not becoming a `shelter_in_place` directive.
- Shelter In Place Warning remaining a true `shelter_in_place` directive.
- Impact-aware warning escalation using real NWS `properties.parameters` fields:
  `thunderstormDamageThreat`, `flashFloodDamageThreat`, `snowSquallImpact`,
  and `WEAHandling`.
- Missing or unknown NWS impact parameters not forcing critical push.
- CDC/NRC host validation and fake-host rejection.
- Hazard notification safety snapshot serialization in `AlertPushTargetingTest`.

Implemented backend template-wide coverage in `AlertTemplateCoverageTest` for:

- Every production template carrying protective action, compatibility,
  dispatch/guidance, safety-review, evidence, or an explicit blocked reason.
- Approved production templates carrying a human approval date, with blocked
  templates remaining unapproved.
- Evidence URL hostnames being exact-allowlisted, including intentional
  subdomains.
- Evidence `supports[]` values staying non-empty and mapped to known template
  fields/future-normalization markers.
- Extreme Wind carrying product-specific NWS provenance.
- Compatibility response values mapping to known CAP protective actions.
- Extreme Wind not resolving to Tornado copy.
- Storm Warning not resolving to Tropical Storm copy.
- Snow Squall Warning using dedicated short-fused travel handling.
- Tsunami Advisory and Tsunami Watch resolving to distinct templates.
- Generic Air Quality Alert not borrowing smoke-specific N95/Wildfire guidance.
- Severe Thunderstorm copy not containing active-storm unplug guidance.
- Extreme Wind copy not containing tornado/basement/lowest-floor guidance.
- Extreme Cold Watch split from Freeze Watch / Freeze Warning.
- Freeze Warning staying prepare-mode.
- Magnitude-only USGS earthquake copy saying reported nearby, not felt nearby.
- Blocked civil/law templates remaining official-only while carrying semantic
  evidence.
- Template/review-matrix parity.

Updated `AlertFeedServiceTest` so feed cards prove official issuer wording stays
available while approved SitPrep copy renders with visible SitPrep attribution.

Added frontend alert-enrichment coverage proving old local fallback guidance is
not revived when the backend safety contract says `official_only`.

Added active-situation CTA coverage:

- Backend `PlanActivationMapServiceTest` proves `evacuate` and
  `shelter_in_place` override saved meeting-place navigation only when a
  non-terminal governing alert exists.
- Backend coverage also proves `avoid_area` and
  `follow_official_instruction` make official guidance primary without
  inventing evacuation/shelter destination advice.
- Frontend `activeSituation.test.js` proves the same fallback semantics when an
  older or partial response must be normalized locally.
- Frontend `mapSummaryModel.test.js` proves saved meeting/destination rows are
  secondary under avoid/official guidance.

Still missing from this pass: resource-distribution tests and frontend visual
regression tests for the map safety drawer.
