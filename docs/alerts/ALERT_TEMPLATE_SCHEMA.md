# Alert Template Schema

Date: 2026-08-27

This document describes the production schema actually loaded by
`AlertDispatchService` from `src/main/resources/templates/alert-dispatch-templates.json`.
The older `provider/event` examples in design notes are illustrative only; the
runtime vocabulary is the one below.

## Required Fields

- `source`: Provider key. Current production values are `NWS`, `USGS`, and `FEMA`.
- `tier`: Legacy grouping value, currently `warning` or `watch`.
- `hazardType`: Canonical hazard wire value accepted by `HazardType`.
- `headline`: Short SitPrep-authored title.
- `body`: Short SitPrep-authored summary. Must not rely on unsupported placeholders.
- `steps`: Two or three short SitPrep-authored action lines.
- `protectiveAction`: One `AlertSafetyPolicy.ProtectiveAction` value describing what the SitPrep copy asks the user to do.
- `compatibleResponseTypes`: CAP response values that may receive this SitPrep action copy.
- `incompatibleResponseTypes`: CAP response values that must suppress this SitPrep action copy.
- `sitprep.dispatchMode`: Explicit dispatch policy. Valid wires are `critical_push`, `attention`, `prepare`, `feed`, and `suppress`.
- `sitprep.guidanceMode`: Explicit guidance policy. Valid wires are `supplement_official`, `official_only`, and `no_guidance`.
- `sitprep.movementDirective`: Official movement directive. Valid wires are `none`, `evacuate`, `shelter_in_place`, `avoid_area`, and `follow_official_instruction`.
- `sitprep.impactAware`: Boolean. When true, `AlertSafetyPolicy` may escalate from the default dispatch mode to `critical_push` only when normalized structured impact fields support it.
- `safetyReview.status`: Template certification state. Valid values are `draft`, `source_verified`, `blocked`, and `approved`.
- `safetyReview.version`: Integer review version for the template wording and metadata.
- `safetyReview.sourceVerifiedAt`: ISO date the cited sources and metadata were checked.
- `safetyReview.approvedAt`: ISO date of human approval, or `null` when the template is not approved.

## Matching Fields

- `eventAny`: Exact NWS product names. Matching is case-insensitive exact equality against `NormalizedAlert.event`.
- `incidentTypeAny`: FEMA incident type fragments matched against the normalized FEMA headline.
- `minMag`: USGS magnitude floor parsed from the normalized USGS headline.
- `_fallback`: FEMA catch-all marker. Fallback templates must appear after specific FEMA templates.

## Optional Fields

- `askTag`: FE Ask guide tag. It is not decorative; matching tags can route users to hazard-specific Ask content. A template must leave this `null` when the matching guide does not exist or the hazard mapping would be misleading.
- `blockedReason`: Required when `safetyReview.status` is `blocked` and the template has no evidence.
- `evidence`: Required for `source_verified` and `approved` templates. Blocked templates may omit evidence only when `blockedReason` states why source-backed SitPrep guidance is unsafe or unavailable.

## Evidence Metadata

Each evidence item has:

- `agency`: Human-readable authority, such as `NOAA / National Weather Service`.
- `title`: Page or document title checked.
- `url`: Official source URL.
- `checkedAt`: ISO date the source was checked.
- `supports`: Template fields supported by that exact source, such as `body`, `steps[0]`, `eventAny`, `sitprep.dispatchMode`, or `futureImpactNormalization`. Do not list a body or step unless the source actually supports that specific claim.

Evidence host validation uses exact hostnames, not `.gov` suffix matching. A URL
such as `weather.gov.example.com` must fail. Legitimate subdomains are added
intentionally, one host at a time.

## Safety Review States

- `draft`: Authored but not source-verified.
- `source_verified`: The template wording is supported by cited official sources and has compatibility metadata, but has not been human-approved.
- `blocked`: SitPrep-authored guidance must not be used because the source or action semantics are ambiguous.
- `approved`: Explicit human safety approval.

Runtime production guidance still requires `approved`. `source_verified` keeps
official issuer text available and suppresses SitPrep-authored guidance.
After the 2026-08-27 user approval, 48 source-verified production templates are
approved with `approvedAt = 2026-08-27`; the four civil/law rows remain
`blocked` and unapproved.

## Placeholder Rules

Supported production placeholders are currently:

- `{mag}` for USGS magnitude copy.
- `{place}` for USGS location copy.

No other placeholder may appear in production template copy unless
`AlertDispatchService.fillBody` and template tests are updated together.

## Dispatch And Guidance Rules

`tier` is retained for legacy grouping, tests, and visual language. It is not the
authoritative push policy once `sitprep.dispatchMode` exists. `AlertSafetyPolicy`
reads `sitprep.dispatchMode`, `sitprep.impactAware`, `sitprep.guidanceMode`,
`sitprep.movementDirective`, lifecycle state, CAP response types, template
compatibility, and safety approval status to decide whether an alert should
push, appear in-feed, use official-only text, or be suppressed.

For impact-aware NWS templates, the ingest layer preserves
`properties.parameters` as `NormalizedAlert.parameters`. The current policy
uses only documented/live NWS parameter names:

- `thunderstormDamageThreat=CONSIDERABLE|DESTRUCTIVE` for Severe Thunderstorm Warning.
- `flashFloodDamageThreat=CONSIDERABLE|CATASTROPHIC` for Flash Flood Warning.
- `snowSquallImpact=SIGNIFICANT` or `WEAHandling=WEA` for Snow Squall Warning.

Flood Warning remains lower/default unless a future real feed field provides a
trustworthy structured high-impact signal. CAP severity/urgency/certainty and
response type alone no longer escalate an `impactAware` weather warning.

`protectiveAction` describes what the SitPrep safety copy asks the user to do.
`sitprep.movementDirective` is narrower: it describes whether officials are
actually telling people to evacuate, shelter in place, avoid an area, or follow
an official action that SitPrep must not infer. General indoor-safety guidance,
heat exposure reduction, smoke avoidance, and Drop/Cover/Hold do not become
official shelter-in-place directives.

## Fallback Rules

There is no generic safety-copy fallback. If no matching template exists,
runtime returns no SitPrep guidance. If a template is unapproved, blocked,
incompatible, or has bad provenance, runtime uses official issuer text when
available and no guidance otherwise.

## Versioning Expectations

Increment `safetyReview.version` whenever body, steps, protective action,
compatibility metadata, dispatch mode, guidance mode, evidence, or review status
changes. Hazard push notifications carry a compact dispatch-time safety snapshot
in `NotificationLog.additionalData`, but historical alert-post records do not
yet persist the full decision. See `ALERT_SAFETY_ARCHITECTURE_PLAN.md` for the
durable auditability follow-up.
