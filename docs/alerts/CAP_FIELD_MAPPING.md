# CAP Field Mapping

Date: 2026-08-27

## Current Normalized Fields

`AlertIngestService.NormalizedAlert` currently preserves:

- `id`: NWS/CAP identifier or provider id.
- `source`: provider identity (`NWS`, `USGS`, `FEMA`).
- `event`: NWS product name.
- `severity`: CAP/provider severity equivalent.
- `urgency`: CAP urgency.
- `certainty`: CAP certainty.
- `messageType`: CAP message type.
- `status`: CAP status.
- `response`: CAP response as a single string.
- `headline`, `description`, `instruction`: official issuer wording.
- `area`: provider area/areaDesc.
- `startedAt`, `endsAt`: onset/effective/sent and ends/expires fallbacks.
- `geometry`: raw GeoJSON map.
- `ugc`, `same`: NWS geocodes.
- `references`: CAP identifiers this alert replaces.

## Missing Or Incomplete Fields

- `responseType[]`: needs collection semantics plus raw unknown preservation.
- `scope`: needed to suppress non-public messages when a provider exposes it.
- `sender` and `sent`: useful for provenance and lifecycle audit.
- `eventCode`: needed for future structured matching where product names are insufficient.
- `sourceSystem`: currently approximated by `source`, but should distinguish provider/feed when IPAWS or additional CAP feeds land.
- USGS impact fields: PAGER, MMI/felt reports, tsunami flag.
- FEMA declaration program/freshness fields are partly summarized but not fully exposed as alert detail.

## Provider Differences

- NWS active GeoJSON carries CAP-like fields and is the closest CAP source in this codebase. Ingest already drops non-`Actual` rows.
- USGS is not CAP. Earthquakes are point-in-time observations and should not inherit CAP lifecycle or response semantics.
- FEMA OpenFEMA declarations are recovery/assistance rows, not live protective-action warnings. Geometry is absent.

## Required Policy Use

Lifecycle and dispatch cannot read only the product name. Policy must evaluate `status`, `messageType`, `scope`, `responseType[]`, `urgency`, `severity`, `certainty`, expiry, provider identity, and template metadata.

## 2026-08-27 Implementation Status

`AlertIngestService.NormalizedAlert` now preserves the CAP-adjacent fields needed
by the first safety-policy pass: `sender`, `sent`, `scope`, `eventCodes`,
`responseTypes`, `unknownResponseTypes`, and `sourceSystem`.

NWS response values now travel as an array. Known CAP response values are
normalized into policy actions; unknown raw values are retained for audit and
force an official-only/no-guidance decision rather than generic SitPrep copy.

Provider limits remain explicit:

- NWS is the only current feed with CAP-like public alert semantics.
- USGS earthquakes are observational hazards and use `Assess` as their policy
  action rather than CAP lifecycle semantics.
- FEMA declarations remain recovery/resource records and should not be treated
  as live protective-action warnings without a future source-specific contract.
